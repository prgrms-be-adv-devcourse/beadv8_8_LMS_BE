package com.hapbang.lecture.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureEventExternalizationConfig;
import com.hapbang.shared.lecture.event.LectureEventTopic;
import com.hapbang.shared.lecture.event.OrderItemCancelCompletedEvent;
import com.hapbang.shared.lecture.event.OrderItemPaidEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

import tools.jackson.databind.json.JsonMapper;

/**
 * 쿠폰 사용 완료·복원용 결제·취소 완료 이벤트가 커밋 뒤 {@code lecture.events.v1}에 {@code order-item:<주문 항목 ID>} key와
 * {@code eventType}·{@code eventVersion} 헤더로 나가고, 본문을 같은 레코드로 다시 읽을 수 있는지 실제 Kafka로 확인한다.
 * 외부화를 켠 설정이라 컨텍스트를 따로 띄우고, 끝나면 닫는다.
 */
@IntegrationTest
@Import(KafkaTestcontainersConfig.class)
@TestPropertySource(properties = {"spring.kafka.admin.auto-create=true",
        "spring.modulith.events.externalization.enabled=true"})
@DirtiesContext
class OrderItemCouponEventKafkaTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Instant AT = Instant.parse("2026-11-09T01:00:00Z");

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    private KafkaConsumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        consumer = consumerAtEnd();
    }

    @AfterEach
    void tearDown() {
        consumer.close();
        received.clear();
    }

    @Test
    void 결제_완료와_취소_완료_이벤트는_주문_항목_key와_eventType_헤더로_강의_토픽에_나간다() {
        long paidItemId = 9_100_000L + (System.nanoTime() % 1_000_000L);
        long canceledItemId = paidItemId + 1;
        OrderItemPaidEvent paid = OrderItemPaidEvent.of(7L, paidItemId, 2L,
                List.of(new OrderItemPaidEvent.Usage(11L, 3L)), AT);
        OrderItemCancelCompletedEvent canceled = OrderItemCancelCompletedEvent.of(7L, canceledItemId, 2L,
                List.of(new OrderItemCancelCompletedEvent.Usage(12L, 4L)), null, List.of(), AT);

        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(paid);
            eventPublisher.publishEvent(canceled);
        });

        ConsumerRecord<String, String> paidRecord = awaitRecord("order-item:" + paidItemId);
        assertHeaders(paidRecord, "OrderItemPaidEvent", OrderItemPaidEvent.class);
        assertThat(paid.eventId()).isEqualTo("order-item-paid:" + paidItemId);
        assertThat(jsonMapper.readValue(paidRecord.value(), OrderItemPaidEvent.class)).isEqualTo(paid);

        ConsumerRecord<String, String> canceledRecord = awaitRecord("order-item:" + canceledItemId);
        assertHeaders(canceledRecord, "OrderItemCancelCompletedEvent", OrderItemCancelCompletedEvent.class);
        assertThat(canceled.eventId()).isEqualTo("order-item-cancel-completed:" + canceledItemId);
        assertThat(jsonMapper.readValue(canceledRecord.value(), OrderItemCancelCompletedEvent.class))
                .isEqualTo(canceled);
    }

    private static void assertHeaders(ConsumerRecord<String, String> record, String eventType, Class<?> recordType) {
        assertThat(header(record, LectureEventExternalizationConfig.EVENT_TYPE)).isEqualTo(eventType);
        assertThat(header(record, LectureEventExternalizationConfig.EVENT_VERSION)).isEqualTo("1");
        assertThat(header(record, "__TypeId__")).isEqualTo(recordType.getName());
    }

    /** 이 토픽을 읽는 소비자. 파티션을 받을 때까지 읽은 뒤 끝으로 옮겨, 이후 이 테스트가 만든 메시지만 받는다 */
    private KafkaConsumer<String, String> consumerAtEnd() {
        Map<String, Object> properties = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "order-item-coupon-event-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        KafkaConsumer<String, String> topicConsumer = new KafkaConsumer<>(properties, new StringDeserializer(),
                new StringDeserializer());
        topicConsumer.subscribe(List.of(LectureEventTopic.NAME));
        await().atMost(TIMEOUT).pollInSameThread().until(() -> {
            topicConsumer.poll(Duration.ofMillis(100));
            return !topicConsumer.assignment().isEmpty();
        });
        topicConsumer.seekToEnd(topicConsumer.assignment());
        topicConsumer.assignment().forEach(topicConsumer::position);
        return topicConsumer;
    }

    private ConsumerRecord<String, String> awaitRecord(String key) {
        await().atMost(TIMEOUT).pollInSameThread().untilAsserted(() -> {
            consumer.poll(Duration.ofMillis(200)).forEach(received::add);
            assertThat(received).anyMatch(record -> key.equals(record.key()));
        });
        return received.stream().filter(record -> key.equals(record.key())).findFirst().orElseThrow();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
