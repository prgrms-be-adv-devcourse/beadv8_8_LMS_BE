package com.hapbang.auxiliary.notification.in.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.appearance.dto.AppearanceCancelRequestDto;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestAcceptedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestRejectedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnActionRequiredEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnCompletedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionWithdrawnEvent;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * 출연 취소 요청 3종·이의 제기 결과 4종(N6)을 실제 Kafka로 받아 알림을 만드는지 본다(ND-082~ND-087·ND-096).
 * test-spec NT-GEN-17·18, NT-KAF-08·09, NT-IDEM-06
 * <p>
 * 토픽·그룹은 이 클래스 전용(UUID), partition 하나라 마지막에 보낸 표식 이벤트가 처리되면 앞선 레코드도 끝난 것이다.
 */
@IntegrationTest
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, NotificationCancelRequestAndObjectionAlertTest.Topics.class})
class NotificationCancelRequestAndObjectionAlertTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String APPEARANCE_TOPIC = "appearance.events.v1.test-" + SUFFIX;
    private static final String LECTURE_TOPIC = "lecture.events.v1.test-" + SUFFIX;
    private static final String APPEARANCE_GROUP = "notification-appearance-test-" + SUFFIX;
    private static final String LECTURE_GROUP = "notification-lecture-test-" + SUFFIX;
    private static final String APPEARANCE_DLT = APPEARANCE_TOPIC + "." + APPEARANCE_GROUP + ".DLT";
    private static final String LECTURE_DLT = LECTURE_TOPIC + "." + LECTURE_GROUP + ".DLT";
    private static final String LECTURE_PACKAGE = NotificationKafkaConstants.LECTURE_EVENTS_TRUSTED_PACKAGE;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Instant START_AT = Instant.parse("2026-01-01T00:00:00Z");
    /** 2026-11-11 10:00 KST */
    private static final OffsetDateTime AFTER_START = OffsetDateTime.parse("2026-11-11T10:00:00+09:00");
    private static final AtomicLong NEXT_ID = new AtomicLong(700_000);

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
        // 알림 소비자는 첫 Backfill 뒤에 시작한다(ND-078). 이 시험은 Backfill을 끄고 직접 시작한다
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

    // 출연 취소 요청 3종 (NT-GEN-17)

    @Test
    void 출연_취소_요청_도착은_호스트에게_게스트_닉네임으로_만든다() {
        long host = member(Role.INFLUENCER, UserStatus.ACTIVE);
        long guest = member(Role.INFLUENCER, UserStatus.ACTIVE, "게스트님");
        AppearanceCancelRequestDto cancelRequest = cancelRequest(host, guest, "겨울 합방");

        kafka.send(APPEARANCE_TOPIC, "appearance-cancel:1",
                new AppearanceCancelRequestedEvent(cancelRequest, LocalDateTime.parse("2026-11-11T10:00:00")));
        awaitAppearanceProcessed();

        assertThat(rows(host)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "APPEARANCE_CANCEL_REQUESTED")
                    .containsEntry("source_event_type", "AppearanceCancelRequestedEvent")
                    .containsEntry("source_target_id", String.valueOf(cancelRequest.cancelRequestId()))
                    .containsEntry("target_type", "APPEARANCE")
                    .containsEntry("target_id", String.valueOf(cancelRequest.appearanceId()))
                    .containsEntry("message", "게스트님님이 '겨울 합방' 출연 취소를 요청했습니다.");
            assertThat(occurredAt(row)).isEqualTo(AFTER_START.toInstant());
        });
        assertThat(rows(guest)).isEmpty();
    }

    @Test
    void 파기된_게스트가_보낸_출연_취소_요청은_파기_닉네임으로_만든다() {
        long host = member(Role.INFLUENCER, UserStatus.ACTIVE);
        long guest = nextId();
        users.save(NotificationUser.createMasked(guest, Instant.parse("2026-11-10T00:00:00Z")));

        kafka.send(APPEARANCE_TOPIC, "appearance-cancel:masked",
                new AppearanceCancelRequestedEvent(cancelRequest(host, guest, "파기 합방"),
                        LocalDateTime.parse("2026-11-11T10:00:00")));
        awaitAppearanceProcessed();

        assertThat(messages(host)).containsExactly("탈퇴회원#" + guest + "님이 '파기 합방' 출연 취소를 요청했습니다.");
    }

    @Test
    void 출연_취소_요청_수락과_거절_자동_거절은_게스트에게_만든다() {
        long host = member(Role.INFLUENCER, UserStatus.ACTIVE);
        long guest = member(Role.INFLUENCER, UserStatus.ACTIVE);
        LocalDateTime at = LocalDateTime.parse("2026-11-11T10:00:00");

        kafka.send(APPEARANCE_TOPIC, "c:1",
                new AppearanceCancelRequestAcceptedEvent(cancelRequest(host, guest, "가을 합방"), at));
        kafka.send(APPEARANCE_TOPIC, "c:2",
                new AppearanceCancelRequestRejectedEvent(cancelRequest(host, guest, "봄 합방"), false, at));
        kafka.send(APPEARANCE_TOPIC, "c:3",
                new AppearanceCancelRequestRejectedEvent(cancelRequest(host, guest, "여름 합방"), true, at));
        awaitAppearanceProcessed();

        assertThat(messages(guest)).containsExactly(
                "'가을 합방' 출연 취소 요청이 수락되어 출연이 취소되었습니다.",
                "'봄 합방' 출연 취소 요청이 거절되었습니다. 출연은 예정대로 진행됩니다.",
                "호스트가 응답하지 않아 '여름 합방' 출연 취소 요청이 자동 거절되었습니다. 출연은 예정대로 진행됩니다.");
        assertThat(rows(host)).isEmpty();
    }

    @Test
    void 필수_값이_빠진_출연_취소_요청은_DLT로_보낸다() {
        long host = member(Role.INFLUENCER, UserStatus.ACTIVE);
        long guest = member(Role.INFLUENCER, UserStatus.ACTIVE);
        AppearanceCancelRequestDto noTitle = new AppearanceCancelRequestDto(nextId(), nextId(), null, host, guest, 1,
                LocalDateTime.parse("2026-11-20T20:00:00"));

        kafka.send(APPEARANCE_TOPIC, "appearance-cancel:no-title",
                new AppearanceCancelRequestAcceptedEvent(noTitle, LocalDateTime.parse("2026-11-11T10:00:00")));

        new MemberEventKafka(bootstrapServers, APPEARANCE_TOPIC)
                .awaitDeadLetter(APPEARANCE_DLT, "appearance-cancel:no-title");
        assertThat(rows(guest)).isEmpty();
    }

    // 게스트 닉네임을 읽는 순서(ND-085, NotificationAlertService.cancelRequested)

    @Test
    void 받을_수_없는_호스트에게_온_출연_취소_요청은_게스트_복제본이_없어도_닉네임을_읽지_않고_DLT_없이_건너뛴다() {
        long host = member(Role.INFLUENCER, UserStatus.WITHDRAWN);
        long guestWithoutReplica = nextId();

        kafka.send(APPEARANCE_TOPIC, "appearance-cancel:withdrawn", new AppearanceCancelRequestedEvent(
                cancelRequest(host, guestWithoutReplica, "탈퇴 합방"), LocalDateTime.parse("2026-11-11T10:00:00")));
        awaitAppearanceProcessed();

        assertThat(rows(host)).isEmpty();
        assertThat(deadLetterKeys(APPEARANCE_DLT)).doesNotContain("appearance-cancel:withdrawn");
    }

    @Test
    void 시작_시각보다_이른_출연_취소_요청은_게스트_복제본이_없어도_닉네임을_읽지_않고_DLT_없이_건너뛴다() {
        long host = member(Role.INFLUENCER, UserStatus.ACTIVE);
        long guestWithoutReplica = nextId();

        kafka.send(APPEARANCE_TOPIC, "appearance-cancel:old", new AppearanceCancelRequestedEvent(
                cancelRequest(host, guestWithoutReplica, "옛 합방"), LocalDateTime.parse("2025-12-31T10:00:00")));
        awaitAppearanceProcessed();

        assertThat(rows(host)).isEmpty();
        assertThat(deadLetterKeys(APPEARANCE_DLT)).doesNotContain("appearance-cancel:old");
    }

    @Test
    void 받을_수_있는_호스트에게_온_출연_취소_요청에서_게스트_복제본이_없으면_재시도_뒤_DLT로_보낸다() {
        long host = member(Role.INFLUENCER, UserStatus.ACTIVE);
        long guestWithoutReplica = nextId();

        kafka.send(APPEARANCE_TOPIC, "appearance-cancel:missing", new AppearanceCancelRequestedEvent(
                cancelRequest(host, guestWithoutReplica, "빈 상대 합방"), LocalDateTime.parse("2026-11-11T10:00:00")));

        new MemberEventKafka(bootstrapServers, APPEARANCE_TOPIC)
                .awaitDeadLetter(APPEARANCE_DLT, "appearance-cancel:missing");
        assertThat(rows(host)).isEmpty();
    }

    // 이의 제기 결과 4종 (NT-GEN-18)

    @Test
    void 이의_제기_인정은_구매자와_판매자에게_문구를_나눠_만든다() {
        long buyer = member(Role.USER, UserStatus.ACTIVE);
        long seller = member(Role.INFLUENCER, UserStatus.ACTIVE);
        long inquiryId = nextId();
        long orderItemId = nextId();

        kafka.send(LECTURE_TOPIC, "inquiry:" + inquiryId, new LectureObjectionResolvedEvent("cmd-1", inquiryId,
                orderItemId, true, buyer, seller, "자바 기초", AFTER_START));
        awaitLectureProcessed();

        assertThat(rows(buyer)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "LECTURE_OBJECTION_RESOLVED")
                    .containsEntry("source_event_type", "LectureObjectionResolvedEvent")
                    .containsEntry("source_target_id", String.valueOf(inquiryId))
                    .containsEntry("target_type", "LECTURE_ORDER_ITEM")
                    .containsEntry("target_id", String.valueOf(orderItemId))
                    .containsEntry("message", "'자바 기초' 이의 제기가 인정되어 HP를 반환합니다.");
            assertThat(occurredAt(row)).isEqualTo(AFTER_START.toInstant());
        });
        assertThat(rows(seller)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("target_id", String.valueOf(orderItemId))
                .containsEntry("message", "'자바 기초' 이의 제기가 인정되었습니다."));
    }

    @Test
    void 이의_제기_기각과_반환_완료_철회_반환_지연을_받는_사람에게_만든다() {
        long buyer = member(Role.USER, UserStatus.ACTIVE);
        long seller = member(Role.INFLUENCER, UserStatus.ACTIVE);

        kafka.send(LECTURE_TOPIC, "i:1", new LectureObjectionResolvedEvent("cmd-2", nextId(), nextId(), false, buyer,
                seller, "기각 강의", AFTER_START));
        kafka.send(LECTURE_TOPIC, "i:2", new LectureObjectionReturnCompletedEvent(nextId(), nextId(), buyer, seller,
                "반환 강의", AFTER_START));
        kafka.send(LECTURE_TOPIC, "i:3", new LectureObjectionWithdrawnEvent("cmd-3", nextId(), nextId(), buyer, seller,
                "철회 강의", AFTER_START));
        kafka.send(LECTURE_TOPIC, "i:4", new LectureObjectionReturnActionRequiredEvent(nextId(), nextId(), buyer,
                seller, "지연 강의", 1, AFTER_START));
        awaitLectureProcessed();

        assertThat(messages(buyer)).containsExactly(
                "'기각 강의' 이의 제기가 기각되었습니다.",
                "'반환 강의' 이의 제기 HP 반환이 완료되었습니다.",
                "'지연 강의' HP 반환이 늦어지고 있습니다. 완료되면 다시 알려 드립니다.");
        assertThat(messages(seller)).containsExactly(
                "'기각 강의' 이의 제기가 기각되었습니다.",
                "'철회 강의' 이의 제기가 철회되었습니다.");
    }

    @Test
    void 알림_시작_시각보다_이른_이의_제기_결과는_DLT_없이_건너뛴다() {
        long buyer = member(Role.USER, UserStatus.ACTIVE);
        long seller = member(Role.INFLUENCER, UserStatus.ACTIVE);

        kafka.send(LECTURE_TOPIC, "lecture-old", new LectureObjectionReturnCompletedEvent(nextId(), nextId(), buyer,
                seller, "옛 강의", OffsetDateTime.parse("2025-12-31T23:59:59Z")));
        awaitLectureProcessed();

        assertThat(rows(buyer)).isEmpty();
        assertThat(deadLetterKeys(LECTURE_DLT)).doesNotContain("lecture-old");
    }

    // 반환 지연 회차 (NT-IDEM-06)

    @Test
    void 반환_지연은_회차마다_한_건이고_재전송은_더_만들지_않는다() {
        long buyer = member(Role.USER, UserStatus.ACTIVE);
        long seller = member(Role.INFLUENCER, UserStatus.ACTIVE);
        long inquiryId = nextId();
        long orderItemId = nextId();

        for (int sequence : new int[] {1, 2, 1}) {
            kafka.send(LECTURE_TOPIC, "inquiry:" + inquiryId, new LectureObjectionReturnActionRequiredEvent(inquiryId,
                    orderItemId, buyer, seller, "회차 강의", sequence, AFTER_START.plusMinutes(sequence)));
        }
        awaitLectureProcessed();

        assertThat(rows(buyer)).extracting(row -> row.get("source_target_id"))
                .containsExactly(inquiryId + ":1", inquiryId + ":2");
    }

    // #818 추가 필드가 빠진 이벤트 (NT-KAF-08, ND-086)

    @Test
    void 추가_필드_키가_모두_없는_818_이전_형식은_알림_없이_DLT로도_보내지_않는다() {
        long inquiryId = nextId();

        kafka.sendRaw(LECTURE_TOPIC, "legacy:" + inquiryId, LECTURE_PACKAGE + ".LectureObjectionResolvedEvent",
                "{\"commandId\":\"cmd-legacy\",\"inquiryId\":" + inquiryId + ",\"orderItemId\":" + nextId()
                        + ",\"accepted\":true}");
        awaitLectureProcessed();

        assertThat(sourceRows(inquiryId)).isEmpty();
        assertThat(deadLetterKeys(LECTURE_DLT)).doesNotContain("legacy:" + inquiryId);
    }

    @Test
    void 추가_필드_값이_null이면_DLT로_보낸다() {
        long inquiryId = nextId();

        kafka.sendRaw(LECTURE_TOPIC, "null-value:" + inquiryId,
                LECTURE_PACKAGE + ".LectureObjectionWithdrawnEvent",
                "{\"commandId\":\"cmd-null\",\"inquiryId\":" + inquiryId + ",\"orderItemId\":1,\"buyerUserId\":null,"
                        + "\"sellerUserId\":null,\"lectureTitle\":null,\"withdrawnAt\":null}");

        new MemberEventKafka(bootstrapServers, LECTURE_TOPIC).awaitDeadLetter(LECTURE_DLT, "null-value:" + inquiryId);
        assertThat(sourceRows(inquiryId)).isEmpty();
    }

    @Test
    void JSON_객체가_아닌_본문은_DLT로_보낸다() {
        kafka.sendRaw(LECTURE_TOPIC, "not-object", LECTURE_PACKAGE + ".LectureObjectionResolvedEvent", "[]");

        new MemberEventKafka(bootstrapServers, LECTURE_TOPIC).awaitDeadLetter(LECTURE_DLT, "not-object");
    }

    @Test
    void 추가_필드_키가_일부만_없으면_DLT로_보낸다() {
        long seller = member(Role.INFLUENCER, UserStatus.ACTIVE);
        long inquiryId = nextId();

        kafka.sendRaw(LECTURE_TOPIC, "partial:" + inquiryId, LECTURE_PACKAGE + ".LectureObjectionWithdrawnEvent",
                "{\"commandId\":\"cmd-partial\",\"inquiryId\":" + inquiryId + ",\"orderItemId\":1,\"sellerUserId\":"
                        + seller + ",\"lectureTitle\":\"일부 강의\",\"withdrawnAt\":\"2026-11-11T10:00:00+09:00\"}");

        new MemberEventKafka(bootstrapServers, LECTURE_TOPIC).awaitDeadLetter(LECTURE_DLT, "partial:" + inquiryId);
        assertThat(rows(seller)).isEmpty();
    }

    @Test
    void 알림_대상이_아닌_강의_이벤트는_건너뛰고_신뢰_패키지_밖_타입은_DLT로_보낸다() {
        kafka.sendRaw(LECTURE_TOPIC, "lecture-changed", LECTURE_PACKAGE + ".LectureChangedEvent", "{\"lectureId\":1}");
        kafka.sendRaw(LECTURE_TOPIC, "lecture-future", LECTURE_PACKAGE + ".LectureFutureEvent", "{}");
        kafka.sendRaw(LECTURE_TOPIC, "outside", "java.util.HashMap", "{}");
        kafka.sendRaw(LECTURE_TOPIC, "no-type", null, "{}");

        MemberEventKafka dlt = new MemberEventKafka(bootstrapServers, LECTURE_TOPIC);
        dlt.awaitDeadLetter(LECTURE_DLT, "outside");
        dlt.awaitDeadLetter(LECTURE_DLT, "no-type");
        awaitLectureProcessed();
        assertThat(deadLetterKeys(LECTURE_DLT)).doesNotContain("lecture-changed", "lecture-future");
    }

    // 리스너 재사용 (NT-KAF-09, ND-087)

    @Test
    void 그룹과_토픽마다_리스너_컨테이너가_하나다() {
        Set<String> pairs = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            String[] topics = container.getContainerProperties().getTopics();
            for (String topic : topics == null ? new String[0] : topics) {
                String pair = container.getGroupId() + "|" + topic;
                if (!pairs.add(pair)) {
                    duplicates.add(pair);
                }
            }
        }

        assertThat(duplicates).isEmpty();
        assertThat(pairs).contains(APPEARANCE_GROUP + "|" + APPEARANCE_TOPIC, LECTURE_GROUP + "|" + LECTURE_TOPIC);
        assertThat(NotificationAlertListenerStarter.ALERT_LISTENERS)
                .contains(NotificationLectureEventListener.LISTENER_ID);
    }

    private AppearanceCancelRequestDto cancelRequest(long host, long guest, String title) {
        return new AppearanceCancelRequestDto(nextId(), nextId(), title, host, guest, 1,
                LocalDateTime.parse("2026-11-20T20:00:00"));
    }

    private long member(Role role, UserStatus status) {
        return member(role, status, "회원");
    }

    private long member(Role role, UserStatus status, String nickname) {
        long id = nextId();
        users.save(NotificationUser.create(new UserDto(id, nickname, role, status, 1L)));
        return id;
    }

    private List<Map<String, Object>> rows(long receiver) {
        return jdbc.queryForList("select * from notification.notification where receiver_user_id = ? order by id",
                receiver);
    }

    private List<Map<String, Object>> sourceRows(long sourceTargetId) {
        return jdbc.queryForList("select * from notification.notification where source_target_id = ?",
                String.valueOf(sourceTargetId));
    }

    private List<Object> messages(long receiver) {
        return rows(receiver).stream().map(row -> row.get("message")).toList();
    }

    private static Instant occurredAt(Map<String, Object> row) {
        return ((Timestamp) row.get("occurred_at")).toInstant();
    }

    /** 표식 반환 완료 알림이 생기면 그 앞의 레코드도 처리가 끝났다(partition 하나) */
    private void awaitLectureProcessed() {
        long marker = member(Role.USER, UserStatus.ACTIVE);
        kafka.send(LECTURE_TOPIC, "marker", new LectureObjectionReturnCompletedEvent(nextId(), nextId(), marker,
                nextId(), "표식", AFTER_START));
        await().atMost(TIMEOUT).until(() -> rows(marker).size() == 1);
    }

    private void awaitAppearanceProcessed() {
        long host = member(Role.INFLUENCER, UserStatus.ACTIVE);
        long marker = member(Role.INFLUENCER, UserStatus.ACTIVE);
        kafka.send(APPEARANCE_TOPIC, "marker", new AppearanceCancelRequestAcceptedEvent(
                cancelRequest(host, marker, "표식"), LocalDateTime.parse("2026-11-11T10:00:00")));
        await().atMost(TIMEOUT).until(() -> rows(marker).size() == 1);
    }

    private List<String> deadLetterKeys(String dlt) {
        return new MemberEventKafka(bootstrapServers, dlt).deadLetterKeysNow(dlt);
    }

    private static long nextId() {
        return NEXT_ID.incrementAndGet();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic notificationAppearanceEventN6TestTopic() {
            return TopicBuilder.name(APPEARANCE_TOPIC).partitions(1).replicas(1).build();
        }

        @Bean
        NewTopic notificationLectureEventTestTopic() {
            return TopicBuilder.name(LECTURE_TOPIC).partitions(1).replicas(1).build();
        }
    }
}
