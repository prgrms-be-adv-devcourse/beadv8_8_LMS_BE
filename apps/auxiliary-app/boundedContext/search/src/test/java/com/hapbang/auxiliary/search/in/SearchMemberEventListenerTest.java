package com.hapbang.auxiliary.search.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

import com.hapbang.auxiliary.search.config.SearchElasticsearchConfig;
import com.hapbang.auxiliary.search.in.kafka.SearchKafkaConfig;
import com.hapbang.auxiliary.search.in.kafka.SearchKafkaSettings;
import com.hapbang.auxiliary.search.out.InfluencerIndexDefinition;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;
import com.hapbang.auxiliary.search.support.SearchElasticsearchTestcontainersConfig;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

/**
 * 회원 이벤트 소비(#771). 실제 Kafka에 회원 앱이 보내는 모양(JSON + {@code __TypeId__} 헤더)으로 보내 검색 문서에 반영되는지 본다.
 * 토픽·그룹은 이 클래스 전용이다.
 */
@IntegrationTest
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, SearchElasticsearchTestcontainersConfig.class,
        SearchMemberEventListenerTest.Topics.class})
class SearchMemberEventListenerTest {

    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String TOPIC = "member.events.v1.test-" + SUFFIX;
    private static final String GROUP = "auxiliary-app.search-member.test-" + SUFFIX;
    private static final String EVENT_PACKAGE = "com.hapbang.shared.member.event.";

    @TestBean(name = SearchKafkaConfig.MEMBER_EVENT_SETTINGS)
    private SearchKafkaSettings settings;

    @Autowired
    @Qualifier(SearchElasticsearchConfig.CLIENT)
    private ElasticsearchClient client;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private static KafkaProducer<String, byte[]> producer;

    static SearchKafkaSettings settings() {
        return SearchKafkaSettings.of(TOPIC, GROUP, 1, Duration.ofMillis(100), Duration.ofMillis(100));
    }

    @DynamicPropertySource
    static void enableListener(DynamicPropertyRegistry registry) {
        registry.add(SearchKafkaConfig.ENABLED, () -> "true");
    }

    @BeforeEach
    void reset() throws Exception {
        SearchElasticsearch.reset(client);
    }

    @AfterAll
    static void closeProducer() {
        if (producer != null) {
            producer.close();
        }
    }

    @Test
    @DisplayName("가입·변경 이벤트로 문서를 넣고 숨긴다. 로그아웃 같은 다른 타입은 건너뛴다")
    void appliesJoinedAndModified() {
        send("10", "UserJoinedEvent", user(10, "INFLUENCER", 1));
        send("10", "UserLoggedOutEvent", "{\"userId\":10,\"loggedOutAt\":\"2026-11-10T10:00:00\"}");
        send("11", "UserJoinedEvent", user(11, "INFLUENCER", 1));
        send("11", "UserModifiedEvent", user(11, "USER", 2));

        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
            assertThat(source(10)).containsEntry("nickname", "닉네임10").containsEntry("hidden", false)
                    .containsEntry("memberVersion", 1);
            assertThat(source(11)).containsEntry("hidden", true).containsEntry("memberVersion", 2)
                    .doesNotContainKey("nickname");
        });
    }

    @Test
    @DisplayName("회원 정보가 빠진 이벤트는 다시 시도하지 않고 <topic>.<group>.DLT로 보낸다")
    void invalidToDeadLetter() {
        send("12", "UserModifiedEvent", "{\"user\":{\"id\":12}}");

        try (KafkaConsumer<String, byte[]> consumer = deadLetterConsumer()) {
            consumer.subscribe(List.of(settings().deadLetterTopic()));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
                assertThat(records).extracting(ConsumerRecord::key).contains("12");
            });
        }
    }

    private static String user(long id, String role, long version) {
        return """
                {"user":{"id":%d,"nickname":"닉네임%d","role":"%s","status":"ACTIVE","version":%d,
                "contentCategories":["GAME"],"bio":"자기소개"}}""".formatted(id, id, role, version);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> source(long userId) throws Exception {
        return client.get(g -> g.index(InfluencerIndexDefinition.ALIAS).id(String.valueOf(userId)), Map.class).source();
    }

    private void send(String key, String type, String json) {
        if (producer == null) {
            producer = new KafkaProducer<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
        }
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(TOPIC, key, json.getBytes(StandardCharsets.UTF_8));
        record.headers().add("__TypeId__", (EVENT_PACKAGE + type).getBytes(StandardCharsets.UTF_8));
        producer.send(record);
        producer.flush();
    }

    private KafkaConsumer<String, byte[]> deadLetterConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + SUFFIX);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return new KafkaConsumer<>(props);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic searchTestMemberTopic() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }
    }
}
