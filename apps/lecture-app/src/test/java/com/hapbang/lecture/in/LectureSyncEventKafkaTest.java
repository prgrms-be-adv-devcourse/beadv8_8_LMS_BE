package com.hapbang.lecture.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

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
import com.hapbang.shared.lecture.domain.LectureConfirmationType;
import com.hapbang.shared.lecture.domain.LecturePurchaseResult;
import com.hapbang.shared.lecture.dto.CartItemSyncDto;
import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LectureOrderItemDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.shared.lecture.event.CartItemChangedEvent;
import com.hapbang.shared.lecture.event.LectureApprovedEvent;
import com.hapbang.shared.lecture.event.LectureChangedEvent;
import com.hapbang.shared.lecture.event.LectureEventTopic;
import com.hapbang.shared.lecture.event.LectureObjectionFiledEvent;
import com.hapbang.shared.lecture.event.LectureOrderHoldFailedEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemCanceledEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemConfirmedEvent;
import com.hapbang.shared.lecture.event.LectureOrderPaidEvent;
import com.hapbang.shared.lecture.event.LecturePurchaseChangedEvent;
import com.hapbang.shared.lecture.event.LectureRejectedEvent;
import com.hapbang.shared.lecture.event.LectureReviewChangedEvent;
import com.hapbang.shared.lecture.event.OrderItemCancelCompletedEvent;
import com.hapbang.shared.lecture.event.OrderItemPaidEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

import tools.jackson.databind.json.JsonMapper;

/**
 * AI 추천용 동기화 이벤트 4종(#712)이 커밋 뒤 {@code lecture.events.v1}에 종류별 key와 {@code eventType}·{@code eventVersion} 헤더로
 * 나가는지 실제 Kafka로 확인한다. 받는 쪽은 {@code __TypeId__} 헤더로 레코드 클래스를 찾는다.
 * 쿠폰 사용 완료·복원용 결제·취소 완료 이벤트와 알림용 이벤트 7종(#872)도 같은 토픽이라 여기서 함께 확인한다.
 * 외부화를 켠 설정이라 컨텍스트를 따로 띄우고, 끝나면 닫는다. 테스트 DB 연결 수가 한도에 가까워 같은 설정의 Kafka 확인은
 * 클래스를 나누지 않고 이 컨텍스트 하나에 모은다.
 */
@IntegrationTest
@Import(KafkaTestcontainersConfig.class)
@TestPropertySource(properties = {"spring.kafka.admin.auto-create=true",
        "spring.modulith.events.externalization.enabled=true"})
@DirtiesContext
class LectureSyncEventKafkaTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-06T09:00:00+09:00");
    private static final Instant COUPON_EVENT_AT = Instant.parse("2026-11-09T01:00:00Z");

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
    void 동기화_이벤트는_종류별_key와_eventType_헤더로_강의_토픽에_나간다() {
        long id = 9_000_000L + (System.nanoTime() % 1_000_000L);
        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new LectureChangedEvent(new LectureSyncDto(id, 1L, "강의", null, true, false,
                    List.of(new LectureContentSyncDto(id, "1강", 1)), 3L)));
            eventPublisher.publishEvent(new LecturePurchaseChangedEvent(new LecturePurchaseSyncDto(id, 2L, id,
                    LecturePurchaseResult.OWNED, AT, AT, 1L)));
            eventPublisher.publishEvent(new CartItemChangedEvent(new CartItemSyncDto(id, 2L, id, false, AT, 0L)));
            eventPublisher.publishEvent(new LectureReviewChangedEvent(new LectureReviewSyncDto(id, id, 2L, 5, true,
                    false, 0L)));
        });

        assertRecord("lecture:" + id, LectureEventExternalizationConfig.LECTURE_CHANGED, LectureChangedEvent.class,
                "\"lecture\":{\"id\":" + id);
        assertRecord("order-item:" + id, LectureEventExternalizationConfig.PURCHASE_CHANGED,
                LecturePurchaseChangedEvent.class, "\"purchase\":{\"orderItemId\":" + id);
        assertRecord("cart-item:" + id, LectureEventExternalizationConfig.CART_ITEM_CHANGED,
                CartItemChangedEvent.class, "\"cartItem\":{\"id\":" + id);
        assertRecord("lecture-review:" + id, LectureEventExternalizationConfig.REVIEW_CHANGED,
                LectureReviewChangedEvent.class, "\"review\":{\"id\":" + id);
    }

    @Test
    void 결제_완료와_취소_완료_이벤트는_주문_항목_key와_eventType_헤더로_강의_토픽에_나간다() {
        long paidItemId = 9_100_000L + (System.nanoTime() % 1_000_000L);
        long canceledItemId = paidItemId + 1;
        OrderItemPaidEvent paid = OrderItemPaidEvent.of(7L, paidItemId, 2L,
                List.of(new OrderItemPaidEvent.Usage(11L, 3L)), COUPON_EVENT_AT);
        OrderItemCancelCompletedEvent canceled = OrderItemCancelCompletedEvent.of(7L, canceledItemId, 2L,
                List.of(new OrderItemCancelCompletedEvent.Usage(12L, 4L)), null, List.of(), COUPON_EVENT_AT);

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

    @Test
    void 알림용_이벤트_7종은_계약한_key와_eventType_헤더로_강의_토픽에_나간다() {
        long id = 9_200_000L + (System.nanoTime() % 1_000_000L);
        List<LectureOrderItemDto> items = List.of(new LectureOrderItemDto(id, id, "OBS 방송 세팅", 9_000L));
        LectureApprovedEvent approved = new LectureApprovedEvent(id, 1L, "OBS 방송 세팅", 1, AT);
        LectureRejectedEvent rejected = new LectureRejectedEvent(id + 1, 1L, "OBS 방송 세팅", "음질이 낮습니다.", 2, AT);
        LectureOrderPaidEvent paid = new LectureOrderPaidEvent(id, 2L, items, AT);
        LectureOrderHoldFailedEvent failed = new LectureOrderHoldFailedEvent(id + 1, 2L, items, AT);
        LectureOrderItemCanceledEvent canceled = new LectureOrderItemCanceledEvent(id, 2L, "OBS 방송 세팅", 9_000L, AT);
        LectureOrderItemConfirmedEvent confirmed = new LectureOrderItemConfirmedEvent(id + 1, 2L, "OBS 방송 세팅",
                LectureConfirmationType.AUTO, AT);
        LectureObjectionFiledEvent filed = new LectureObjectionFiledEvent(500L, id + 2, 2L, 1L, "OBS 방송 세팅", AT);

        transactionTemplate.executeWithoutResult(status -> List.of(approved, rejected, paid, failed, canceled,
                confirmed, filed).forEach(eventPublisher::publishEvent));

        assertPublished("lecture:" + id, "LectureApprovedEvent", approved);
        assertPublished("lecture:" + (id + 1), "LectureRejectedEvent", rejected);
        assertPublished("order:" + id, "LectureOrderPaidEvent", paid);
        assertPublished("order:" + (id + 1), "LectureOrderHoldFailedEvent", failed);
        assertPublished("order-item:" + id, "LectureOrderItemCanceledEvent", canceled);
        assertPublished("order-item:" + (id + 1), "LectureOrderItemConfirmedEvent", confirmed);
        assertPublished("order-item:" + (id + 2), "LectureObjectionFiledEvent", filed);
    }

    /** key와 헤더가 계약대로이고, 받는 쪽이 {@code __TypeId__}로 읽은 값이 보낸 이벤트와 같다(사건 시각 {@code +09:00} 포함) */
    private void assertPublished(String key, String eventType, Object event) {
        ConsumerRecord<String, String> record = awaitRecord(key, eventType);
        assertHeaders(record, eventType, event.getClass());
        assertThat(jsonMapper.readValue(record.value(), event.getClass())).isEqualTo(event);
    }

    private void assertRecord(String key, String eventType, Class<?> recordType, String payloadPart) {
        ConsumerRecord<String, String> record = awaitRecord(key);
        assertHeaders(record, eventType, recordType);
        assertThat(record.value()).contains(payloadPart);
    }

    private static void assertHeaders(ConsumerRecord<String, String> record, String eventType, Class<?> recordType) {
        assertThat(header(record, LectureEventExternalizationConfig.EVENT_TYPE)).isEqualTo(eventType);
        assertThat(header(record, LectureEventExternalizationConfig.EVENT_VERSION)).isEqualTo("1");
        assertThat(header(record, "__TypeId__")).isEqualTo(recordType.getName());
    }

    /** 이 토픽을 읽는 소비자. 파티션을 받을 때까지 읽은 뒤 끝으로 옮겨, 이후 이 테스트가 만든 메시지만 받는다 */
    private KafkaConsumer<String, String> consumerAtEnd() {
        Map<String, Object> properties = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "lecture-sync-test-" + UUID.randomUUID());
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

    /** 같은 key에 이벤트 종류가 여럿일 수 있어(주문 항목의 취소·확정·접수) {@code eventType}까지 맞는 레코드를 기다린다 */
    private ConsumerRecord<String, String> awaitRecord(String key, String eventType) {
        Predicate<ConsumerRecord<String, String>> matches = record -> key.equals(record.key())
                && eventType.equals(header(record, LectureEventExternalizationConfig.EVENT_TYPE));
        await().atMost(TIMEOUT).pollInSameThread().untilAsserted(() -> {
            consumer.poll(Duration.ofMillis(200)).forEach(received::add);
            assertThat(received).anyMatch(matches);
        });
        return received.stream().filter(matches).findFirst().orElseThrow();
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
