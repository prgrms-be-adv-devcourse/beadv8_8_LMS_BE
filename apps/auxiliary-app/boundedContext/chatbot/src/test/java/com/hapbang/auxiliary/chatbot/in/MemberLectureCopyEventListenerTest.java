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

import com.hapbang.auxiliary.chatbot.in.kafka.ChatbotKafkaConfig;
import com.hapbang.auxiliary.chatbot.in.kafka.ChatbotKafkaSettings;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

/**
 * 회원·강의 이벤트 소비 (#717). 실제 Kafka에 회원·강의 앱이 보내는 모양(JSON + {@code __TypeId__} 헤더)으로 보내 사본·마스킹에 반영되는지 본다.
 * 토픽·그룹은 이 클래스 전용이다.
 */
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, MemberLectureCopyEventListenerTest.Topics.class})
class MemberLectureCopyEventListenerTest extends ChatbotIntegrationTestBase {

    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String MEMBER_TOPIC = "member.events.v1.test-" + SUFFIX;
    private static final String MEMBER_GROUP = "auxiliary-app.chatbot-member-copy.test-" + SUFFIX;
    private static final String LECTURE_TOPIC = "lecture.events.v1.test-" + SUFFIX;
    private static final String LECTURE_GROUP = "auxiliary-app.chatbot-lecture-copy.test-" + SUFFIX;
    private static final String MEMBER_EVENT = "com.hapbang.shared.member.event.";
    private static final String LECTURE_EVENT = "com.hapbang.shared.lecture.event.";

    @TestBean(name = ChatbotKafkaConfig.MEMBER_EVENT_SETTINGS, methodName = "memberSettings")
    private ChatbotKafkaSettings memberSettings;

    @TestBean(name = ChatbotKafkaConfig.LECTURE_EVENT_SETTINGS, methodName = "lectureSettings")
    private ChatbotKafkaSettings lectureSettings;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private static KafkaProducer<String, byte[]> producer;

    static ChatbotKafkaSettings memberSettings() {
        return ChatbotKafkaSettings.of(MEMBER_TOPIC, MEMBER_GROUP, ChatbotKafkaSettings.MEMBER_TRUSTED_PACKAGE, 1,
                Duration.ofMillis(100), Duration.ofMillis(100));
    }

    static ChatbotKafkaSettings lectureSettings() {
        return ChatbotKafkaSettings.of(LECTURE_TOPIC, LECTURE_GROUP, ChatbotKafkaSettings.LECTURE_TRUSTED_PACKAGE, 1,
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
    @DisplayName("회원 가입·변경은 사본에, 강의 4종은 사본에 반영하고, 로그아웃·이의 제기 같은 다른 타입은 건너뛴다")
    void applies() {
        send(MEMBER_TOPIC, "1", MEMBER_EVENT + "UserModifiedEvent", """
                {"user":{"id":1,"nickname":"회원","role":"INFLUENCER","status":"ACTIVE","version":3,
                "contentCategories":["GAME"],"bio":"소개"}}""");
        send(MEMBER_TOPIC, "1", MEMBER_EVENT + "UserLoggedOutEvent", """
                {"userId":1,"loggedOutAt":"2026-11-09T01:00:00Z"}""");
        send(LECTURE_TOPIC, "lecture:5", LECTURE_EVENT + "LectureChangedEvent", """
                {"lecture":{"id":5,"sellerUserId":9,"title":"강의","description":null,"onSale":true,"deleted":false,
                "contents":[{"id":1,"title":"1강","sequence":1}],"version":2}}""");
        send(LECTURE_TOPIC, "order-item:7", LECTURE_EVENT + "LecturePurchaseChangedEvent", """
                {"purchase":{"orderItemId":7,"buyerUserId":1,"lectureId":5,"result":"OWNED",
                "orderedAt":"2026-11-08T10:00:00+09:00","purchasedAt":"2026-11-08T10:01:00+09:00","version":1}}""");
        send(LECTURE_TOPIC, "cart-item:8", LECTURE_EVENT + "CartItemChangedEvent", """
                {"cartItem":{"id":8,"userId":1,"lectureId":5,"deleted":false,"addedAt":"2026-11-08T10:00:00+09:00",
                "version":0}}""");
        send(LECTURE_TOPIC, "lecture-review:9", LECTURE_EVENT + "LectureReviewChangedEvent", """
                {"review":{"id":9,"lectureId":5,"reviewerUserId":1,"rating":4,"counted":true,"deleted":false,"version":0}}""");
        send(LECTURE_TOPIC, "order-item:7", LECTURE_EVENT + "LectureObjectionWithdrawnEvent",
                "{\"commandId\":\"c-1\",\"inquiryId\":1,\"orderItemId\":7}");

        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
            assertThat(rows.row("member_copy", "id", 1)).containsEntry("role", "INFLUENCER").containsEntry("version", 3L);
            assertThat(rows.row("lecture_copy", "id", 5)).containsEntry("version", 2L);
            assertThat(rows.row("lecture_purchase_copy", "order_item_id", 7)).containsEntry("result", "OWNED");
            assertThat(rows.row("cart_item_copy", "id", 8)).containsEntry("deleted", false);
            assertThat(rows.row("lecture_review_copy", "id", 9)).containsEntry("rating", 4);
        });
    }

    @Test
    @DisplayName("마스킹 이벤트는 그 회원 데이터를 지우고, 뒤에 온 그 회원의 구매 이벤트는 버린다")
    void masking() {
        send(LECTURE_TOPIC, "order-item:70", LECTURE_EVENT + "LecturePurchaseChangedEvent", """
                {"purchase":{"orderItemId":70,"buyerUserId":2,"lectureId":5,"result":"OWNED",
                "orderedAt":"2026-11-08T10:00:00+09:00","purchasedAt":null,"version":0}}""");
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() ->
                assertThat(rows.count("lecture_purchase_copy")).isEqualTo(1));

        send(MEMBER_TOPIC, "2", MEMBER_EVENT + "UserMaskedEvent", """
                {"userId":2,"maskedAt":"2026-11-09T01:00:00Z"}""");
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
            assertThat(rows.maskedMembers()).containsExactly(2L);
            assertThat(rows.count("lecture_purchase_copy")).isZero();
        });

        send(LECTURE_TOPIC, "order-item:71", LECTURE_EVENT + "LecturePurchaseChangedEvent", """
                {"purchase":{"orderItemId":71,"buyerUserId":2,"lectureId":5,"result":"OWNED",
                "orderedAt":"2026-11-08T10:00:00+09:00","purchasedAt":null,"version":0}}""");
        // 버린 것을 확인하려고 뒤에 다른 회원의 구매를 보내 처리 순서를 기다린다(같은 파티션)
        send(LECTURE_TOPIC, "order-item:71", LECTURE_EVENT + "LecturePurchaseChangedEvent", """
                {"purchase":{"orderItemId":72,"buyerUserId":3,"lectureId":5,"result":"OWNED",
                "orderedAt":"2026-11-08T10:00:00+09:00","purchasedAt":null,"version":0}}""");
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() ->
                assertThat(rows.row("lecture_purchase_copy", "order_item_id", 72)).isNotEmpty());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chatbot.lecture_purchase_copy WHERE buyer_user_id = 2", Integer.class)).isZero();
    }

    @Test
    @DisplayName("필수 값이 빠진 회원 이벤트는 다시 시도하지 않고 <topic>.<group>.DLT로 보낸다")
    void invalidToDeadLetter() {
        send(MEMBER_TOPIC, "4", MEMBER_EVENT + "UserJoinedEvent", "{\"user\":{\"id\":4}}");

        try (KafkaConsumer<String, byte[]> consumer = deadLetterConsumer()) {
            consumer.subscribe(List.of(memberSettings().deadLetterTopic()));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
                assertThat(records).extracting(ConsumerRecord::key).contains("4");
            });
        }
        assertThat(rows.count("member_copy")).isZero();
    }

    private void send(String topic, String key, String type, String json) {
        if (producer == null) {
            producer = new KafkaProducer<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
        }
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, json.getBytes(StandardCharsets.UTF_8));
        record.headers().add("__TypeId__", type.getBytes(StandardCharsets.UTF_8));
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
        NewTopic chatbotTestMemberTopic() {
            return TopicBuilder.name(MEMBER_TOPIC).partitions(1).replicas(1).build();
        }

        @Bean
        NewTopic chatbotTestLectureTopic() {
            return TopicBuilder.name(LECTURE_TOPIC).partitions(1).replicas(1).build();
        }
    }
}
