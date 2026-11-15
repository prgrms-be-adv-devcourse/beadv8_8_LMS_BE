package com.hapbang.auxiliary.notification.in.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.lecture.domain.LectureConfirmationType;
import com.hapbang.shared.lecture.dto.LectureOrderItemDto;
import com.hapbang.shared.lecture.event.LectureApprovedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionFiledEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnCompletedEvent;
import com.hapbang.shared.lecture.event.LectureOrderHoldFailedEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemCanceledEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemConfirmedEvent;
import com.hapbang.shared.lecture.event.LectureOrderPaidEvent;
import com.hapbang.shared.lecture.event.LectureRejectedEvent;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * 강의 진행 이벤트 7종(N7 강의, M4)을 실제 Kafka로 받아 알림을 만드는지 본다(ND-114~ND-117).
 * test-spec NT-GEN-21, NT-KAF-09
 * <p>
 * 토픽·그룹은 이 클래스 전용(UUID), partition 하나라 마지막에 보낸 표식 이벤트가 처리되면 앞선 레코드도 끝난 것이다.
 */
@IntegrationTest
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, NotificationLectureProgressAlertTest.Topics.class})
class NotificationLectureProgressAlertTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String LECTURE_TOPIC = "lecture.events.v1.progress-test-" + SUFFIX;
    private static final String LECTURE_GROUP = "notification-lecture-progress-test-" + SUFFIX;
    private static final String LECTURE_DLT = LECTURE_TOPIC + "." + LECTURE_GROUP + ".DLT";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Instant START_AT = Instant.parse("2026-01-01T00:00:00Z");
    /** 2026-11-11 10:00 KST */
    private static final OffsetDateTime AFTER_START = OffsetDateTime.parse("2026-11-11T10:00:00+09:00");
    /** 회원 ID 대역. 같은 DB를 쓰는 다른 시험 클래스(출연 800_000, 지갑 2_100_000 등)와 겹치지 않게 둔다 */
    private static final AtomicLong NEXT_ID = new AtomicLong(2_200_000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(NotificationKafkaConstants.LECTURE_EVENTS_ENABLED, () -> "true");
        registry.add("hapbang.notification.alert.start-at", START_AT::toString);
    }

    @TestBean(name = NotificationKafkaConfig.LECTURE_EVENT_SETTINGS)
    private NotificationKafkaConsumerSettings lectureEventSettings;

    static NotificationKafkaConsumerSettings lectureEventSettings() {
        return new NotificationKafkaConsumerSettings(LECTURE_TOPIC, LECTURE_GROUP, LECTURE_DLT, 1,
                Duration.ofMillis(100), NotificationKafkaConstants.RETRY_MAX_INTERVAL,
                NotificationKafkaConstants.DEAD_LETTER_SEND_TIMEOUT, 1, NotificationKafkaConstants.DEAD_LETTER_REPLICAS);
    }

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Autowired
    private NotificationUserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    private AlertEventKafka kafka;

    @BeforeEach
    void setUp() {
        kafka = new AlertEventKafka(bootstrapServers);
        // 알림 소비자는 첫 Backfill 뒤에 시작한다(ND-078). 이 시험은 Backfill을 끄고 직접 시작한다
        MessageListenerContainer container = listenerRegistry.getListenerContainer(
                NotificationLectureEventListener.LISTENER_ID);
        if (!container.isRunning()) {
            container.start();
        }
    }

    @AfterEach
    void tearDown() {
        kafka.close();
    }

    @Test
    void 검수_승인과_반려는_판매자에게_강의로_이동하는_알림을_만든다() {
        long seller = member(Role.INFLUENCER);
        long lectureId = nextId();

        kafka.send(LECTURE_TOPIC, "lecture:" + lectureId,
                new LectureRejectedEvent(lectureId, seller, "자바 기초", "영상이 비었습니다.", 1, AFTER_START));
        kafka.send(LECTURE_TOPIC, "lecture:" + lectureId,
                new LectureApprovedEvent(lectureId, seller, "자바 기초", 2, AFTER_START.plusMinutes(1)));
        awaitProcessed();

        assertThat(rows(seller)).hasSize(2);
        assertThat(rows(seller).get(0)).containsEntry("kind", "LECTURE_REJECTED")
                .containsEntry("source_event_type", "LectureRejectedEvent")
                .containsEntry("source_target_id", lectureId + ":1")
                .containsEntry("target_type", "LECTURE")
                .containsEntry("target_id", String.valueOf(lectureId))
                .containsEntry("message", "'자바 기초' 강의 검수가 반려되었습니다. 사유: 영상이 비었습니다.");
        assertThat(occurredAt(rows(seller).get(0))).isEqualTo(AFTER_START.toInstant());
        assertThat(rows(seller).get(1)).containsEntry("kind", "LECTURE_APPROVED")
                .containsEntry("source_target_id", lectureId + ":2")
                .containsEntry("target_type", "LECTURE")
                .containsEntry("message", "'자바 기초' 강의 검수가 승인되어 판매가 시작되었습니다.");
    }

    @Test
    void 같은_검수_회차를_다시_받으면_한_건이고_다음_회차는_새_알림이다() {
        long seller = member(Role.INFLUENCER);
        long lectureId = nextId();

        for (int sequence : new int[] {1, 1, 2}) {
            kafka.send(LECTURE_TOPIC, "lecture:" + lectureId,
                    new LectureRejectedEvent(lectureId, seller, "재검수 강의", "사유", sequence, AFTER_START));
        }
        awaitProcessed();

        assertThat(rows(seller)).extracting(row -> row.get("source_target_id"))
                .containsExactly(lectureId + ":1", lectureId + ":2");
    }

    @Test
    void 구매_완료와_결제_실패는_구매자에게_주문으로_이동하는_알림을_만든다() {
        long buyer = member(Role.USER);
        long paidOrder = nextId();
        long failedOrder = nextId();

        kafka.send(LECTURE_TOPIC, "order:" + paidOrder, new LectureOrderPaidEvent(paidOrder, buyer,
                List.of(item("자바 기초"), item("스프링"), item("JPA")), AFTER_START));
        kafka.send(LECTURE_TOPIC, "order:" + failedOrder,
                new LectureOrderHoldFailedEvent(failedOrder, buyer, List.of(item("리액트")), AFTER_START));
        awaitProcessed();

        assertThat(rows(buyer)).hasSize(2);
        assertThat(rows(buyer).get(0)).containsEntry("kind", "LECTURE_ORDER_PAID")
                .containsEntry("source_target_id", String.valueOf(paidOrder))
                .containsEntry("target_type", "LECTURE_ORDER")
                .containsEntry("target_id", String.valueOf(paidOrder))
                .containsEntry("message", "'자바 기초' 외 2개 강의 구매가 완료되었습니다.");
        assertThat(rows(buyer).get(1)).containsEntry("kind", "LECTURE_ORDER_HOLD_FAILED")
                .containsEntry("source_target_id", String.valueOf(failedOrder))
                .containsEntry("target_type", "LECTURE_ORDER")
                .containsEntry("message", "'리액트' 강의 결제에 실패했습니다.");
    }

    @Test
    void 구매_취소와_자동_구매_확정은_구매자에게_주문_항목으로_이동하는_알림을_만든다() {
        long buyer = member(Role.USER);
        long canceledItem = nextId();
        long freeItem = nextId();
        long confirmedItem = nextId();

        kafka.send(LECTURE_TOPIC, "order-item:" + canceledItem,
                new LectureOrderItemCanceledEvent(canceledItem, buyer, "자바 기초", 12_000L, AFTER_START));
        kafka.send(LECTURE_TOPIC, "order-item:" + freeItem,
                new LectureOrderItemCanceledEvent(freeItem, buyer, "무료 강의", 0L, AFTER_START));
        kafka.send(LECTURE_TOPIC, "order-item:" + confirmedItem, new LectureOrderItemConfirmedEvent(confirmedItem,
                buyer, "스프링", LectureConfirmationType.AUTO, AFTER_START));
        awaitProcessed();

        assertThat(rows(buyer)).hasSize(3);
        assertThat(rows(buyer).get(0)).containsEntry("kind", "LECTURE_ORDER_ITEM_CANCELED")
                .containsEntry("source_target_id", String.valueOf(canceledItem))
                .containsEntry("target_type", "LECTURE_ORDER_ITEM")
                .containsEntry("target_id", String.valueOf(canceledItem))
                .containsEntry("message", "'자바 기초' 구매가 취소되어 12,000 HP를 돌려드렸습니다.");
        assertThat(rows(buyer).get(1)).containsEntry("message", "'무료 강의' 구매가 취소되었습니다.");
        assertThat(rows(buyer).get(2)).containsEntry("kind", "LECTURE_ORDER_ITEM_CONFIRMED")
                .containsEntry("source_target_id", String.valueOf(confirmedItem))
                .containsEntry("target_type", "LECTURE_ORDER_ITEM")
                .containsEntry("message", "구매 확정 기한이 지나 '스프링' 구매가 확정되었습니다.");
    }

    @Test
    void 직접_확정과_첫_재생_확정은_알림도_DLT도_없이_넘긴다() {
        long buyer = member(Role.USER);

        kafka.send(LECTURE_TOPIC, "direct", new LectureOrderItemConfirmedEvent(nextId(), buyer, "직접",
                LectureConfirmationType.DIRECT, AFTER_START));
        kafka.send(LECTURE_TOPIC, "first-play", new LectureOrderItemConfirmedEvent(nextId(), buyer, "재생",
                LectureConfirmationType.FIRST_PLAY, AFTER_START));
        awaitProcessed();

        assertThat(rows(buyer)).isEmpty();
        assertThat(deadLetterKeys()).doesNotContain("direct", "first-play");
    }

    @Test
    void 이의_제기_접수는_구매자와_판매자에게_한_건씩_만든다() {
        long buyer = member(Role.USER);
        long seller = member(Role.INFLUENCER);
        long inquiryId = nextId();
        long orderItemId = nextId();

        kafka.send(LECTURE_TOPIC, "order-item:" + orderItemId,
                new LectureObjectionFiledEvent(inquiryId, orderItemId, buyer, seller, "자바 기초", AFTER_START));
        awaitProcessed();

        assertThat(rows(buyer)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("kind", "LECTURE_OBJECTION_FILED")
                .containsEntry("source_target_id", String.valueOf(inquiryId))
                .containsEntry("target_type", "LECTURE_ORDER_ITEM")
                .containsEntry("target_id", String.valueOf(orderItemId))
                .containsEntry("message", "'자바 기초' 이의 제기가 접수되었습니다."));
        assertThat(rows(seller)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("source_target_id", String.valueOf(inquiryId))
                .containsEntry("message", "'자바 기초' 이의 제기가 접수되어 지급이 멈췄습니다."));
    }

    @Test
    void 필수_값이_빠진_강의_진행_이벤트는_DLT로_보낸다() {
        long buyer = member(Role.USER);

        kafka.send(LECTURE_TOPIC, "no-items", new LectureOrderPaidEvent(nextId(), buyer, List.of(), AFTER_START));

        new MemberEventKafka(bootstrapServers, LECTURE_TOPIC).awaitDeadLetter(LECTURE_DLT, "no-items");
        assertThat(rows(buyer)).isEmpty();
    }

    @Test
    void 알림_시작_시각보다_이른_강의_진행_이벤트는_DLT_없이_건너뛴다() {
        long seller = member(Role.INFLUENCER);

        kafka.send(LECTURE_TOPIC, "lecture-old", new LectureApprovedEvent(nextId(), seller, "옛 강의", 1,
                OffsetDateTime.parse("2025-12-31T23:59:59Z")));
        awaitProcessed();

        assertThat(rows(seller)).isEmpty();
        assertThat(deadLetterKeys()).doesNotContain("lecture-old");
    }

    private LectureOrderItemDto item(String title) {
        return new LectureOrderItemDto(nextId(), nextId(), title, 5_000L);
    }

    private long member(Role role) {
        long id = nextId();
        users.save(NotificationUser.create(new UserDto(id, "회원", role, UserStatus.ACTIVE, 1L)));
        return id;
    }

    private List<Map<String, Object>> rows(long receiver) {
        return jdbc.queryForList("select * from notification.notification where receiver_user_id = ? order by id",
                receiver);
    }

    private static Instant occurredAt(Map<String, Object> row) {
        return ((Timestamp) row.get("occurred_at")).toInstant();
    }

    /** 표식 이의 제기 반환 완료 알림이 생기면 그 앞의 레코드도 처리가 끝났다(partition 하나) */
    private void awaitProcessed() {
        long marker = member(Role.USER);
        kafka.send(LECTURE_TOPIC, "marker", new LectureObjectionReturnCompletedEvent(nextId(), nextId(), marker,
                nextId(), "표식", AFTER_START));
        await().atMost(TIMEOUT).until(() -> rows(marker).size() == 1);
    }

    private List<String> deadLetterKeys() {
        return new MemberEventKafka(bootstrapServers, LECTURE_DLT).deadLetterKeysNow(LECTURE_DLT);
    }

    private static long nextId() {
        return NEXT_ID.incrementAndGet();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic notificationLectureProgressTestTopic() {
            return TopicBuilder.name(LECTURE_TOPIC).partitions(1).replicas(1).build();
        }
    }
}
