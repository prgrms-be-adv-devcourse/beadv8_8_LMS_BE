package com.hapbang.auxiliary.chatbot.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.apache.kafka.clients.admin.NewTopic;

import com.hapbang.auxiliary.chatbot.in.kafka.ChatbotKafkaConfig;
import com.hapbang.auxiliary.chatbot.in.kafka.ChatbotKafkaSettings;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

/**
 * 출연 이벤트 소비 (#716). 실제 Kafka에 출연 앱이 보내는 모양(JSON + {@code __TypeId__} 헤더)으로 보내 사본에 반영되는지 본다.
 * 토픽·그룹은 이 클래스 전용이다.
 */
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, AppearanceCopyEventListenerTest.Topics.class})
class AppearanceCopyEventListenerTest extends ChatbotIntegrationTestBase {

    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String TOPIC = "appearance.events.v1.test-" + SUFFIX;
    private static final String GROUP = "auxiliary-app.chatbot-appearance-copy.test-" + SUFFIX;
    private static final String EVENT_PACKAGE = "com.hapbang.shared.appearance.event.";

    @TestBean(name = ChatbotKafkaConfig.APPEARANCE_EVENT_SETTINGS)
    private ChatbotKafkaSettings settings;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private static KafkaProducer<String, byte[]> producer;

    static ChatbotKafkaSettings settings() {
        return ChatbotKafkaSettings.of(TOPIC, GROUP, ChatbotKafkaSettings.APPEARANCE_TRUSTED_PACKAGE, 1,
                Duration.ofMillis(100), Duration.ofMillis(100));
    }

    @DynamicPropertySource
    static void enableListener(DynamicPropertyRegistry registry) {
        registry.add(ChatbotKafkaConfig.ENABLED, () -> "true");
    }

    @AfterAll
    static void closeProducer() {
        if (producer != null) {
            producer.close();
        }
    }

    @Test
    @DisplayName("출연 변경·리뷰 공개 이벤트를 사본에 반영하고, 요청 도착 같은 다른 타입은 건너뛴다")
    void applies() {
        send("appearance:1", "AppearanceChangedEvent", """
                {"appearance":{"id":1,"hostId":10,"guestId":20,"result":"COMPLETED",
                "scheduledAt":"2026-11-20T20:00:00.123456","version":2}}""");
        send("appearance-request:5", "AppearanceRequestSentEvent", """
                {"request":{"requestId":5,"hostId":10,"guestId":20,"title":"합방","fee":1000,
                "scheduledAt":"2026-11-20T20:00:00"}}""");
        send("appearance-review-reviewee:20", "AppearanceReviewPublishedEvent", """
                {"reviewId":7,"appearanceId":1,"reviewerUserId":10,"revieweeUserId":20,"revieweeRole":"GUEST",
                "rating":5,"publishedAt":"2026-11-21T10:00:00"}""");

        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
            assertThat(rows.appearance(1)).containsEntry("result", "COMPLETED").containsEntry("version", 2L)
                    .containsEntry("host_id", 10L).containsEntry("guest_id", 20L);
            assertThat(rows.review(7)).containsEntry("rating", 5).containsEntry("reviewee_user_id", 20L);
        });
    }

    @Test
    @DisplayName("출연 취소 요청 이벤트 3종(#817)은 건너뛰고 DLT로 보내지 않는다. 같은 key의 다음 출연 변경은 그대로 반영한다")
    void skipsCancelRequestEvents() {
        String cancelRequest = """
                {"cancelRequest":{"cancelRequestId":9,"appearanceId":3,"title":"합방","hostId":10,"guestId":20,
                "sequence":1,"scheduledAt":"2026-11-20T20:00:00"}""";
        send("appearance:3", "AppearanceCancelRequestedEvent", cancelRequest + ",\"requestedAt\":\"2026-11-10T10:00:00\"}");
        send("appearance:3", "AppearanceCancelRequestRejectedEvent",
                cancelRequest + ",\"autoRejected\":false,\"rejectedAt\":\"2026-11-10T11:00:00\"}");
        send("appearance:3", "AppearanceCancelRequestAcceptedEvent", cancelRequest + ",\"acceptedAt\":\"2026-11-10T12:00:00\"}");
        send("appearance:3", "AppearanceChangedEvent", """
                {"appearance":{"id":3,"hostId":10,"guestId":20,"result":"CANCELED",
                "scheduledAt":"2026-11-20T20:00:00","version":3}}""");

        // 이 테스트는 프로듀서 하나가 한 건씩 flush해 보내고 토픽 partition이 1개라 보낸 순서대로 처리된다
        // (Modulith 전송은 같은 key라도 순서가 바뀔 수 있다, kafka.md). 출연 변경이 반영됐으면 앞의 세 건은 이미 지나갔고,
        // 처리기는 DLT 전송을 마친 뒤 다음 레코드로 넘어간다. 그래서 일정 시간 지켜보지 않고(testing.md의 during)
        // 이 시점의 DLT를 끝까지 한 번 읽어 확인한다
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() ->
                assertThat(rows.appearance(3)).containsEntry("result", "CANCELED").containsEntry("version", 3L));
        assertThat(deadLetterKeys()).doesNotContain("appearance:3");
    }

    @Test
    @DisplayName("알림용 출연 이벤트(#871: 요청서·모집글·출연 key 접두어 각각)는 건너뛰고 DLT로 보내지 않는다")
    void skipsNotificationEvents() {
        send("appearance-request:11", "AppearanceRequestAcceptedEvent", """
                {"request":{"requestId":11,"hostId":10,"guestId":20,"title":"합방","fee":30000,
                "scheduledAt":"2026-11-20T20:00:00","acceptDeadline":"2026-11-15T20:00:00"},"appearanceId":4,
                "acceptedAt":"2026-11-10T10:00:00"}""");
        send("appearance-recruit:12", "AppearanceRecruitOpenedEvent", """
                {"recruit":{"recruitId":12,"hostId":10,"title":"모집","recruitDeadline":"2026-11-15T20:00:00"},
                "openedAt":"2026-11-10T10:00:00"}""");
        send("appearance:4", "AppearanceCanceledByHostEvent", """
                {"appearance":{"appearanceId":4,"title":"합방","hostId":10,"guestId":20,
                "scheduledAt":"2026-11-20T20:00:00"},"penaltyAmount":9000,"canceledAt":"2026-11-10T11:00:00"}""");
        send("appearance:4", "AppearanceChangedEvent", """
                {"appearance":{"id":4,"hostId":10,"guestId":20,"result":"CANCELED",
                "scheduledAt":"2026-11-20T20:00:00","version":1}}""");

        // 처리 순서와 DLT를 한 번 읽는 근거는 위 skipsCancelRequestEvents와 같다
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() ->
                assertThat(rows.appearance(4)).containsEntry("result", "CANCELED").containsEntry("version", 1L));
        assertThat(deadLetterKeys()).doesNotContain("appearance-request:11", "appearance-recruit:12", "appearance:4");
    }

    @Test
    @DisplayName("필수 값이 빠진 이벤트는 다시 시도하지 않고 <topic>.<group>.DLT로 보낸다")
    void invalidToDeadLetter() {
        send("appearance:2", "AppearanceChangedEvent", "{\"appearance\":{\"id\":2}}");

        // DLT를 읽는 방법(deadLetterKeys)이 실제로 DLT 메시지를 찾는지도 여기서 확인된다
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(deadLetterKeys()).contains("appearance:2"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM chatbot.appearance_copy WHERE id = 2", Integer.class))
                .isZero();
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

    /**
     * DLT의 모든 partition을 직접 배정해 처음부터 지금 끝 offset까지 읽은 key. 그룹 합류를 기다리지 않으므로
     * 한 건도 읽지 못한 채 비어 있다고 판단하는 일이 없다. DLT 토픽은 앱이 뜰 때 선언된다({@code ChatbotKafkaConfig})
     */
    private List<String> deadLetterKeys() {
        String topic = settings().deadLetterTopic();
        try (KafkaConsumer<String, byte[]> consumer = deadLetterConsumer()) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic, Duration.ofSeconds(10)).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            assertThat(partitions).as("DLT 토픽 %s", topic).isNotEmpty();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            List<String> keys = new ArrayList<>();
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> keys.add(record.key()));
                return partitions.stream().allMatch(partition -> consumer.position(partition) >= end.get(partition));
            });
            return keys;
        }
    }

    /** partition을 직접 배정해 읽으므로 그룹이 없다(offset을 커밋하지 않음). 토픽을 자동으로 만들지 않는다 */
    private KafkaConsumer<String, byte[]> deadLetterConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return new KafkaConsumer<>(props);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic chatbotTestAppearanceTopic() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }
    }
}
