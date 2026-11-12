package com.hapbang.auxiliary.search.lecture.in;

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
import org.springframework.util.ClassUtils;

import com.hapbang.auxiliary.search.config.SearchElasticsearchConfig;
import com.hapbang.auxiliary.search.in.kafka.SearchKafkaSettings;
import com.hapbang.auxiliary.search.in.kafka.SearchLectureKafkaConfig;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexDefinition;
import com.hapbang.auxiliary.search.lecture.out.LectureRating;
import com.hapbang.auxiliary.search.lecture.out.LectureReviewCopyStore;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;
import com.hapbang.auxiliary.search.support.SearchElasticsearchTestcontainersConfig;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

/**
 * 강의 이벤트 소비(#882). 실제 Kafka에 강의 앱이 보내는 모양(JSON + {@code __TypeId__} 헤더)으로 보내 강의 검색 문서에 반영되는지, 이 앱에 클래스가
 * 없는 새 타입을 DLT로 보내지 않고 건너뛰는지 본다. 토픽·그룹은 이 클래스 전용이다. 사본(JDBC 표)은 테스트 사이에 비워지지 않으므로 강의·리뷰
 * ID를 900번대로 두어 다른 테스트와 겹치지 않게 한다.
 */
@IntegrationTest
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, SearchElasticsearchTestcontainersConfig.class,
        SearchLectureEventListenerTest.Topics.class})
class SearchLectureEventListenerTest {

    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String TOPIC = "lecture.events.v1.test-" + SUFFIX;
    private static final String GROUP = "auxiliary-app.search-lecture.test-" + SUFFIX;
    private static final String EVENT_PACKAGE = SearchKafkaSettings.LECTURE_TRUSTED_PACKAGE + ".";
    /** 강의 앱이 앞으로 더할 수 있는, 이 앱에 클래스가 없는 이벤트 */
    private static final String MISSING_TYPE = "LectureAddedLaterForSearchTestEvent";

    @TestBean(name = SearchLectureKafkaConfig.LECTURE_EVENT_SETTINGS)
    private SearchKafkaSettings settings;

    @Autowired
    @Qualifier(SearchElasticsearchConfig.CLIENT)
    private ElasticsearchClient client;

    @Autowired
    private LectureReviewCopyStore reviews;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private static KafkaProducer<String, byte[]> producer;

    static SearchKafkaSettings settings() {
        return SearchKafkaSettings.of(TOPIC, GROUP, 1, Duration.ofMillis(100), Duration.ofMillis(100));
    }

    @DynamicPropertySource
    static void enableListener(DynamicPropertyRegistry registry) {
        registry.add(SearchLectureKafkaConfig.ENABLED, () -> "true");
    }

    @BeforeEach
    void reset() throws Exception {
        SearchElasticsearch.reset(client);
        SearchElasticsearch.createLectureIndex(client);
    }

    @AfterAll
    static void closeProducer() {
        if (producer != null) {
            producer.close();
        }
    }

    @Test
    @DisplayName("강의·리뷰 이벤트로 문서를 넣고 평점을 고친다. 구매처럼 쓰지 않는 타입은 건너뛴다")
    void appliesLectureAndReview() {
        send("lecture:911", "LectureChangedEvent", lecture(911, true, 0));
        send("order-item:1", "LecturePurchaseChangedEvent", """
                {"purchase":{"orderItemId":1,"buyerUserId":3,"lectureId":911,"result":"OWNED",
                "orderedAt":"2026-11-12T09:00:00+09:00","version":0}}""");
        send("lecture-review:901", "LectureReviewChangedEvent", review(901, 911, 4, 0));
        send("lecture:912", "LectureChangedEvent", lecture(912, false, 1));

        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
            assertThat(source(911)).containsEntry("title", "강의911").containsEntry("hidden", false)
                    .containsEntry("contentTitles", List.of("1강 설치")).containsEntry("averageRating", 4.0)
                    .containsEntry("reviewCount", 1);
            assertThat(source(912)).containsEntry("hidden", true);
        });
    }

    @Test
    @DisplayName("이 앱에 클래스가 없는 강의 이벤트는 읽지 않고 건너뛴다. DLT로 가지 않고 뒤 이벤트도 막지 않는다(#872·Q3)")
    void skipsMissingType() {
        assertThat(ClassUtils.isPresent(EVENT_PACKAGE + MISSING_TYPE, null)).isFalse();

        send("lecture:913", MISSING_TYPE, "{\"anything\":\"new\"}");
        send("lecture:914", "LectureChangedEvent", lecture(914, true, 0));

        await().atMost(Duration.ofSeconds(30)).ignoreExceptions()
                .untilAsserted(() -> assertThat(source(914)).containsEntry("hidden", false));
        assertThat(deadLetterKeys(Duration.ofSeconds(3))).doesNotContain("lecture:913");
    }

    @Test
    @DisplayName("강의 이벤트 패키지 밖의 타입(java.util 등 역직렬화기 기본 신뢰 타입)은 건너뛰지 않고 다시 시도 없이 DLT로 보낸다")
    void untrustedTypeToDeadLetter() {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(TOPIC, "lecture:916",
                "{\"title\":\"강의 아님\"}".getBytes(StandardCharsets.UTF_8));
        record.headers().add("__TypeId__", "java.util.LinkedHashMap".getBytes(StandardCharsets.UTF_8));
        send(record);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(deadLetterKeys(Duration.ofMillis(500))).contains("lecture:916"));
    }

    @Test
    @DisplayName("본문이 깨진 강의 이벤트는 다시 시도하지 않고 <topic>.<group>.DLT로 보낸다")
    void brokenToDeadLetter() {
        send("lecture:915", "LectureChangedEvent", "{\"lecture\":");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(deadLetterKeys(Duration.ofMillis(500))).contains("lecture:915"));
    }

    @Test
    @DisplayName("alias가 없으면(처음 적재 전) 리뷰는 사본에만 적고 예외 없이 넘긴다. 재시도·DLT로 가지 않는다(L14)")
    void copiesReviewBeforeFirstLoad() throws Exception {
        SearchElasticsearch.reset(client);

        send("lecture-review:902", "LectureReviewChangedEvent", review(902, 921, 5, 0));
        send("lecture:921", "LectureChangedEvent", lecture(921, true, 0));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(reviews.ratingOf(921L)).isEqualTo(new LectureRating(5.0, 1)));
        assertThat(deadLetterKeys(Duration.ofSeconds(3))).doesNotContain("lecture-review:902", "lecture:921");
        assertThat(client.indices().exists(e -> e.index(LectureIndexDefinition.ALIAS)).value()).isFalse();
    }

    private static String lecture(long id, boolean onSale, long version) {
        return """
                {"lecture":{"id":%d,"sellerUserId":7,"title":"강의%d","description":"소개","onSale":%s,"deleted":false,
                "contents":[{"id":1,"title":"1강 설치","sequence":1}],"version":%d}}""".formatted(id, id, onSale, version);
    }

    private static String review(long id, long lectureId, int rating, long version) {
        return """
                {"review":{"id":%d,"lectureId":%d,"reviewerUserId":3,"rating":%d,"counted":true,"deleted":false,
                "version":%d}}""".formatted(id, lectureId, rating, version);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> source(long lectureId) throws Exception {
        return client.get(g -> g.index(LectureIndexDefinition.ALIAS).id(String.valueOf(lectureId)), Map.class)
                .source();
    }

    private void send(String key, String type, String json) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(TOPIC, key, json.getBytes(StandardCharsets.UTF_8));
        record.headers().add("__TypeId__", (EVENT_PACKAGE + type).getBytes(StandardCharsets.UTF_8));
        send(record);
    }

    private void send(ProducerRecord<String, byte[]> record) {
        if (producer == null) {
            producer = new KafkaProducer<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
        }
        producer.send(record);
        producer.flush();
    }

    /** DLT에 쌓인 key를 처음부터 읽는다 */
    private List<String> deadLetterKeys(Duration pollFor) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        List<String> keys = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(settings().deadLetterTopic()));
            long until = System.nanoTime() + pollFor.toNanos();
            while (System.nanoTime() < until) {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> keys.add(record.key()));
            }
        }
        return keys;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic searchTestLectureTopic() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }
    }
}
