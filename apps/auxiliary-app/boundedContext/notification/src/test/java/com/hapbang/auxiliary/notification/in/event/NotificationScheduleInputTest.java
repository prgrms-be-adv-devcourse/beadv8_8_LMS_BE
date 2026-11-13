package com.hapbang.auxiliary.notification.in.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
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

import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceCancelRequestDto;
import com.hapbang.shared.appearance.dto.AppearanceDto;
import com.hapbang.shared.appearance.dto.AppearanceRecruitDto;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.shared.appearance.event.AppearanceAbsentEvent;
import com.hapbang.shared.appearance.event.AppearanceApplicationSelectedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestAcceptedEvent;
import com.hapbang.shared.appearance.event.AppearanceRecruitCanceledEvent;
import com.hapbang.shared.appearance.event.AppearanceRecruitOpenedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestAcceptedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;
import com.hapbang.shared.lecture.domain.LectureConfirmationType;
import com.hapbang.shared.lecture.dto.LectureOrderItemDto;
import com.hapbang.shared.lecture.event.LectureObjectionFiledEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnCompletedEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemCanceledEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemConfirmedEvent;
import com.hapbang.shared.lecture.event.LectureOrderPaidEvent;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

/**
 * 출연·강의 이벤트를 실제 Kafka로 받아 예약을 만들고 취소하는지 본다(N10, ND-133·ND-138·ND-034). 기존 알림 리스너·그룹을 다시 쓴다.
 * 가장 중요한 검증: 같은 예약에 대한 생성·취소 순서 역전, 한 레코드의 예약 처리와 즉시 알림은 한 트랜잭션.
 * test-spec NT-SCH-01~06·11, NT-KAF-01(예약).
 * <p>
 * 토픽·그룹은 이 클래스 전용(UUID), partition 하나라 마지막에 보낸 표식 이벤트가 처리되면 앞선 레코드도 끝난 것이다.
 */
@IntegrationTest
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, NotificationScheduleInputTest.Topics.class})
class NotificationScheduleInputTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String APPEARANCE_TOPIC = "appearance.events.v1.schedule-test-" + SUFFIX;
    private static final String APPEARANCE_GROUP = "notification-appearance-schedule-test-" + SUFFIX;
    private static final String APPEARANCE_DLT = APPEARANCE_TOPIC + "." + APPEARANCE_GROUP + ".DLT";
    private static final String LECTURE_TOPIC = "lecture.events.v1.schedule-test-" + SUFFIX;
    private static final String LECTURE_GROUP = "notification-lecture-schedule-test-" + SUFFIX;
    private static final String LECTURE_DLT = LECTURE_TOPIC + "." + LECTURE_GROUP + ".DLT";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Instant START_AT = Instant.parse("2026-01-01T00:00:00Z");
    /** 지금보다 먼 미래라 업무 기한이 지나지 않는다 */
    private static final LocalDateTime FUTURE = LocalDateTime.now().plusDays(30).withNano(0);
    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);
    private static final AtomicLong NEXT_ID = new AtomicLong(3_000_000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(NotificationKafkaConstants.APPEARANCE_EVENTS_ENABLED, () -> "true");
        registry.add(NotificationKafkaConstants.LECTURE_EVENTS_ENABLED, () -> "true");
        registry.add("hapbang.notification.alert.start-at", START_AT::toString);
    }

    @TestBean(name = NotificationKafkaConfig.APPEARANCE_EVENT_SETTINGS)
    private NotificationKafkaConsumerSettings appearanceEventSettings;

    @TestBean(name = NotificationKafkaConfig.LECTURE_EVENT_SETTINGS)
    private NotificationKafkaConsumerSettings lectureEventSettings;

    static NotificationKafkaConsumerSettings appearanceEventSettings() {
        return settings(APPEARANCE_TOPIC, APPEARANCE_GROUP, APPEARANCE_DLT);
    }

    static NotificationKafkaConsumerSettings lectureEventSettings() {
        return settings(LECTURE_TOPIC, LECTURE_GROUP, LECTURE_DLT);
    }

    private static NotificationKafkaConsumerSettings settings(String topic, String group, String dlt) {
        return new NotificationKafkaConsumerSettings(topic, group, dlt, 1, Duration.ofMillis(100),
                NotificationKafkaConstants.RETRY_MAX_INTERVAL, NotificationKafkaConstants.DEAD_LETTER_SEND_TIMEOUT, 1,
                NotificationKafkaConstants.DEAD_LETTER_REPLICAS);
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
        for (String id : List.of(NotificationAppearanceEventListener.LISTENER_ID,
                NotificationLectureEventListener.LISTENER_ID)) {
            MessageListenerContainer container = listenerRegistry.getListenerContainer(id);
            if (!container.isRunning()) {
                container.start();
            }
        }
    }

    @AfterEach
    void tearDown() {
        kafka.close();
    }

    // NT-SCH-01·02·05: 생성 → 취소 순서

    @Test
    void 요청_발송은_만료_임박을_예약하고_수락은_그것을_취소하며_방송_임박과_출연_확인_부탁을_예약한다() {
        long host = member();
        long guest = member();
        long requestId = nextId();
        long appearanceId = nextId();
        AppearanceRequestDto request = new AppearanceRequestDto(requestId, host, guest, "합방", 1_000L, FUTURE,
                FUTURE.minusDays(1));

        kafka.send(APPEARANCE_TOPIC, "request:" + requestId, new AppearanceRequestSentEvent(request));
        kafka.send(APPEARANCE_TOPIC, "request:" + requestId,
                new AppearanceRequestAcceptedEvent(request, appearanceId, NOW));
        awaitAppearanceProcessed();

        assertThat(status("APPEARANCE_REQUEST_EXPIRING", requestId, guest)).isEqualTo("CANCELED");
        assertThat(status("APPEARANCE_STARTING", appearanceId, host)).isEqualTo("SCHEDULED");
        assertThat(status("APPEARANCE_STARTING", appearanceId, guest)).isEqualTo("SCHEDULED");
        assertThat(status("APPEARANCE_CONFIRM_PERIOD_ENDING", appearanceId, host)).isEqualTo("SCHEDULED");
        // 즉시 알림(출연 요청 도착·요청 수락)도 그대로 만든다
        assertThat(kinds(guest)).contains("APPEARANCE_REQUEST_SENT");
        assertThat(kinds(host)).contains("APPEARANCE_REQUEST_ACCEPTED");
    }

    // NT-SCH-06: 취소 → 생성 순서 역전(Kafka 경로)

    @Test
    void 합의_취소가_지원_선정보다_먼저_와도_방송_임박과_출연_확인_부탁은_취소로_남는다() {
        long host = member();
        long guest = member();
        long appearanceId = nextId();
        long recruitId = nextId();

        kafka.send(APPEARANCE_TOPIC, "appearance:" + appearanceId, new AppearanceCancelRequestAcceptedEvent(
                new AppearanceCancelRequestDto(nextId(), appearanceId, "모집", host, guest, 1, FUTURE), NOW));
        kafka.send(APPEARANCE_TOPIC, "application:" + appearanceId, new AppearanceApplicationSelectedEvent(
                new AppearanceApplicationDto(nextId(), recruitId, "모집", host, guest, NOW), appearanceId, FUTURE, NOW));
        awaitAppearanceProcessed();

        assertThat(status("APPEARANCE_STARTING", appearanceId, host)).isEqualTo("CANCELED");
        assertThat(status("APPEARANCE_STARTING", appearanceId, guest)).isEqualTo("CANCELED");
        assertThat(status("APPEARANCE_CONFIRM_PERIOD_ENDING", appearanceId, host)).isEqualTo("CANCELED");
    }

    // NT-SCH-03·11 (ND-041)

    @Test
    void 모집_시작은_마감_임박을_예약하고_모집_전체_취소는_알림_없이_취소한다() {
        long host = member();
        long recruitId = nextId();
        AppearanceRecruitDto recruit = new AppearanceRecruitDto(recruitId, host, "모집", FUTURE);

        kafka.send(APPEARANCE_TOPIC, "recruit:" + recruitId, new AppearanceRecruitOpenedEvent(recruit, NOW));
        awaitAppearanceProcessed();
        assertThat(status("APPEARANCE_RECRUIT_CLOSING", recruitId, host)).isEqualTo("SCHEDULED");

        kafka.send(APPEARANCE_TOPIC, "recruit:" + recruitId, new AppearanceRecruitCanceledEvent(recruit, NOW));
        awaitAppearanceProcessed();

        assertThat(status("APPEARANCE_RECRUIT_CLOSING", recruitId, host)).isEqualTo("CANCELED");
        assertThat(kinds(host)).isEmpty();
    }

    // 한 레코드의 예약 처리와 즉시 알림은 한 트랜잭션(ND-133)

    @Test
    void 게스트_복제본이_없어_레코드가_실패하면_같은_레코드의_예약도_남지_않는다() {
        long host = member();
        long unknownGuest = nextId();
        long appearanceId = nextId();
        AppearanceRequestDto request = new AppearanceRequestDto(nextId(), host, unknownGuest, "합방", 1_000L, FUTURE,
                FUTURE.minusDays(1));

        kafka.send(APPEARANCE_TOPIC, "accepted-unknown-guest",
                new AppearanceRequestAcceptedEvent(request, appearanceId, NOW));
        awaitAppearanceProcessed();

        await().atMost(TIMEOUT).until(() -> appearanceDeadLetterKeys().contains("accepted-unknown-guest"));
        assertThat(rows("APPEARANCE_STARTING", appearanceId, host)).isEmpty();
        assertThat(rows("APPEARANCE_CONFIRM_PERIOD_ENDING", appearanceId, host)).isEmpty();
        assertThat(kinds(host)).isEmpty();
    }

    @Test
    void 필수_값이_빈_예약_입력_이벤트는_재시도_없이_DLT로_보낸다() {
        long host = member();
        long recruitId = nextId();

        kafka.send(APPEARANCE_TOPIC, "opened-without-deadline",
                new AppearanceRecruitOpenedEvent(new AppearanceRecruitDto(recruitId, host, "모집", null), NOW));
        awaitAppearanceProcessed();

        await().atMost(TIMEOUT).until(() -> appearanceDeadLetterKeys().contains("opened-without-deadline"));
        assertThat(rows("APPEARANCE_RECRUIT_CLOSING", recruitId, host)).isEmpty();
    }

    // NT-SCH-04·05 (강의)

    @Test
    void 구매_완료는_항목마다_구매_취소_마감_임박을_예약하고_구매_취소는_그_항목만_취소한다() {
        long buyer = member();
        long orderId = nextId();
        long canceledItem = nextId();
        long keptItem = nextId();
        OffsetDateTime paidAt = OffsetDateTime.now();

        kafka.send(LECTURE_TOPIC, "order:" + orderId, new LectureOrderPaidEvent(orderId, buyer, List.of(
                new LectureOrderItemDto(canceledItem, 1L, "자바", 1_000L),
                new LectureOrderItemDto(keptItem, 2L, "스프링", 2_000L)), paidAt));
        kafka.send(LECTURE_TOPIC, "order-item:" + canceledItem, new LectureOrderItemCanceledEvent(canceledItem, buyer,
                "자바", 1_000L, paidAt.plusMinutes(1)));
        awaitLectureProcessed();

        assertThat(status("LECTURE_ORDER_ITEM_CONFIRMING", canceledItem, buyer)).isEqualTo("CANCELED");
        assertThat(status("LECTURE_ORDER_ITEM_CONFIRMING", keptItem, buyer)).isEqualTo("SCHEDULED");
        assertThat(kinds(buyer)).contains("LECTURE_ORDER_PAID", "LECTURE_ORDER_ITEM_CANCELED");
    }

    // NT-SCH-06 (강의, ND-143)

    @Test
    void 구매_확정이_구매_완료보다_먼저_와도_구매_취소_마감_임박은_취소로_남고_구매_완료_알림은_만든다() {
        long buyer = member();
        long orderId = nextId();
        long itemId = nextId();
        OffsetDateTime paidAt = OffsetDateTime.now();

        kafka.send(LECTURE_TOPIC, "order-item:" + itemId, new LectureOrderItemConfirmedEvent(itemId, buyer, "자바",
                LectureConfirmationType.DIRECT, paidAt.plusMinutes(5)));
        kafka.send(LECTURE_TOPIC, "order:" + orderId, new LectureOrderPaidEvent(orderId, buyer, List.of(
                new LectureOrderItemDto(itemId, 1L, "자바", 1_000L)), paidAt));
        awaitLectureProcessed();

        assertThat(rows("LECTURE_ORDER_ITEM_CONFIRMING", itemId, buyer)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("status", "CANCELED"));
        assertThat(kinds(buyer)).containsExactly("LECTURE_ORDER_PAID");
    }

    // ND-149: 이의 제기 접수가 구매 취소 마감 임박을 취소한다(순서와 관계없이)

    @Test
    void 이의_제기_접수는_구매_취소_마감_임박을_취소하고_먼저_와도_늦게_온_구매_완료가_예약을_만들지_않는다() {
        long buyer = member();
        long seller = member();
        long afterPaid = nextId();
        long beforePaid = nextId();
        OffsetDateTime paidAt = OffsetDateTime.now();

        kafka.send(LECTURE_TOPIC, "order:" + afterPaid, new LectureOrderPaidEvent(nextId(), buyer, List.of(
                new LectureOrderItemDto(afterPaid, 1L, "자바", 1_000L)), paidAt));
        kafka.send(LECTURE_TOPIC, "order-item:" + afterPaid, new LectureObjectionFiledEvent(nextId(), afterPaid,
                buyer, seller, "자바", paidAt.plusMinutes(1)));
        kafka.send(LECTURE_TOPIC, "order-item:" + beforePaid, new LectureObjectionFiledEvent(nextId(), beforePaid,
                buyer, seller, "스프링", paidAt.plusMinutes(1)));
        kafka.send(LECTURE_TOPIC, "order:" + beforePaid, new LectureOrderPaidEvent(nextId(), buyer, List.of(
                new LectureOrderItemDto(beforePaid, 2L, "스프링", 1_000L)), paidAt));
        awaitLectureProcessed();

        assertThat(status("LECTURE_ORDER_ITEM_CONFIRMING", afterPaid, buyer)).isEqualTo("CANCELED");
        assertThat(status("LECTURE_ORDER_ITEM_CONFIRMING", beforePaid, buyer)).isEqualTo("CANCELED");
        // 이의 제기 접수 알림은 구매자·판매자에게 그대로 만든다
        assertThat(kinds(seller)).containsExactly("LECTURE_OBJECTION_FILED", "LECTURE_OBJECTION_FILED");
    }

    private void awaitAppearanceProcessed() {
        long host = member();
        long marker = member();
        kafka.send(APPEARANCE_TOPIC, "marker", new AppearanceAbsentEvent(
                new AppearanceDto(nextId(), "표식", host, marker, FUTURE), NOW));
        await().atMost(TIMEOUT).until(() -> kinds(marker).size() == 1);
    }

    private void awaitLectureProcessed() {
        long marker = member();
        kafka.send(LECTURE_TOPIC, "marker", new LectureObjectionReturnCompletedEvent(nextId(), nextId(), marker,
                nextId(), "표식", OffsetDateTime.now()));
        await().atMost(TIMEOUT).until(() -> kinds(marker).size() == 1);
    }

    private List<String> appearanceDeadLetterKeys() {
        return new MemberEventKafka(bootstrapServers, APPEARANCE_DLT).deadLetterKeysNow(APPEARANCE_DLT);
    }

    private List<Map<String, Object>> rows(String kind, long target, long receiver) {
        return jdbc.queryForList("""
                select status from notification.notification_schedule
                where kind = ? and target_id = ? and receiver_user_id = ?
                """, kind, target, receiver);
    }

    private String status(String kind, long target, long receiver) {
        List<Map<String, Object>> rows = rows(kind, target, receiver);
        assertThat(rows).as("%s %d %d", kind, target, receiver).hasSize(1);
        return (String) rows.getFirst().get("status");
    }

    private List<String> kinds(long receiver) {
        return jdbc.queryForList("select kind from notification.notification where receiver_user_id = ? order by id",
                String.class, receiver);
    }

    private long member() {
        long id = nextId();
        users.save(NotificationUser.create(new UserDto(id, "회원" + id, Role.INFLUENCER, UserStatus.ACTIVE, 1L)));
        return id;
    }

    private static long nextId() {
        return NEXT_ID.incrementAndGet();
    }

    static class Topics {

        @Bean
        NewTopic notificationAppearanceScheduleTestTopic() {
            return TopicBuilder.name(APPEARANCE_TOPIC).partitions(1).replicas(1).build();
        }

        @Bean
        NewTopic notificationLectureScheduleTestTopic() {
            return TopicBuilder.name(LECTURE_TOPIC).partitions(1).replicas(1).build();
        }
    }
}
