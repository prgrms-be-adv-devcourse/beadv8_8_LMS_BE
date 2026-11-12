package com.hapbang.auxiliary.notification.in.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.appearance.domain.AppearanceConfirmType;
import com.hapbang.shared.appearance.domain.AppearanceRecruitCloseType;
import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceDto;
import com.hapbang.shared.appearance.dto.AppearanceRecruitDto;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.shared.appearance.event.AppearanceAbsentEvent;
import com.hapbang.shared.appearance.event.AppearanceApplicationNotSelectedEvent;
import com.hapbang.shared.appearance.event.AppearanceApplicationSelectedEvent;
import com.hapbang.shared.appearance.event.AppearanceCanceledByHostEvent;
import com.hapbang.shared.appearance.event.AppearanceConfirmedEvent;
import com.hapbang.shared.appearance.event.AppearanceRecruitCanceledEvent;
import com.hapbang.shared.appearance.event.AppearanceRecruitClosedEvent;
import com.hapbang.shared.appearance.event.AppearanceRecruitOpenedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestAcceptedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestCanceledEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestExpiredEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestRejectedEvent;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
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
 * 출연 진행 알림 10종(N7 출연)을 실제 Kafka로 받아 알림을 만드는지 본다(ND-109~ND-112). test-spec NT-GEN-20, NT-KAF-09
 * <p>
 * 토픽·그룹은 이 클래스 전용(UUID), partition 하나라 마지막에 보낸 표식 이벤트가 처리되면 앞선 레코드도 끝난 것이다.
 */
@IntegrationTest
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, NotificationAppearanceProgressAlertTest.Topics.class})
class NotificationAppearanceProgressAlertTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String APPEARANCE_TOPIC = "appearance.events.v1.test-" + SUFFIX;
    private static final String APPEARANCE_GROUP = "notification-appearance-test-" + SUFFIX;
    private static final String APPEARANCE_DLT = APPEARANCE_TOPIC + "." + APPEARANCE_GROUP + ".DLT";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Instant START_AT = Instant.parse("2026-01-01T00:00:00Z");
    /** 2026-11-11 10:00 KST = UTC 01:00 */
    private static final LocalDateTime AT = LocalDateTime.parse("2026-11-11T10:00:00");
    private static final Instant AT_INSTANT = Instant.parse("2026-11-11T01:00:00Z");
    private static final LocalDateTime SCHEDULED = LocalDateTime.parse("2026-12-01T20:00:00");
    private static final AtomicLong NEXT_ID = new AtomicLong(800_000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(NotificationKafkaConstants.APPEARANCE_EVENTS_ENABLED, () -> "true");
        registry.add("hapbang.notification.alert.start-at", START_AT::toString);
    }

    @TestBean(name = NotificationKafkaConfig.APPEARANCE_EVENT_SETTINGS)
    private NotificationKafkaConsumerSettings appearanceEventSettings;

    static NotificationKafkaConsumerSettings appearanceEventSettings() {
        return new NotificationKafkaConsumerSettings(APPEARANCE_TOPIC, APPEARANCE_GROUP, APPEARANCE_DLT, 1,
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
        MessageListenerContainer container = listenerRegistry
                .getListenerContainer(NotificationAppearanceEventListener.LISTENER_ID);
        if (!container.isRunning()) {
            container.start();
        }
    }

    @AfterEach
    void tearDown() {
        kafka.close();
    }

    @Test
    void 요청_수락과_거절은_호스트에게_게스트_닉네임으로_만들고_수락은_성립한_출연으로_이동한다() {
        long host = member("호스트");
        long guest = member("게스트");
        AppearanceRequestDto accepted = request(host, guest, "봄 합방");
        AppearanceRequestDto rejected = request(host, guest, "여름 합방");
        long appearanceId = nextId();

        kafka.send(APPEARANCE_TOPIC, "r:1", new AppearanceRequestAcceptedEvent(accepted, appearanceId, AT));
        kafka.send(APPEARANCE_TOPIC, "r:2", new AppearanceRequestRejectedEvent(rejected, AT));
        awaitProcessed();

        assertThat(rows(host)).satisfiesExactly(row -> {
            assertThat(row).containsEntry("kind", "APPEARANCE_REQUEST_ACCEPTED")
                    .containsEntry("source_event_type", "AppearanceRequestAcceptedEvent")
                    .containsEntry("source_target_id", String.valueOf(accepted.requestId()))
                    .containsEntry("target_type", "APPEARANCE")
                    .containsEntry("target_id", String.valueOf(appearanceId))
                    .containsEntry("message", "게스트님이 '봄 합방' 출연 요청을 수락했습니다. 12월 1일 20:00 방송 예정입니다.");
            assertThat(occurredAt(row)).isEqualTo(AT_INSTANT);
        }, row -> assertThat(row).containsEntry("kind", "APPEARANCE_REQUEST_REJECTED")
                .containsEntry("source_target_id", String.valueOf(rejected.requestId()))
                .containsEntry("target_type", "APPEARANCE_REQUEST")
                .containsEntry("target_id", String.valueOf(rejected.requestId()))
                .containsEntry("message", "게스트님이 '여름 합방' 출연 요청을 거절했습니다."));
        assertThat(rows(guest)).isEmpty();
    }

    @Test
    void 응답_전_취소는_게스트에게_만들고_만료는_호스트와_게스트에게_한_건씩_만든다() {
        long host = member("호스트");
        long guest = member("게스트");
        AppearanceRequestDto canceled = request(host, guest, "가을 합방");
        AppearanceRequestDto expired = request(host, guest, "겨울 합방");
        AppearanceRequestExpiredEvent expiredEvent = new AppearanceRequestExpiredEvent(expired, AT);

        kafka.send(APPEARANCE_TOPIC, "r:3", new AppearanceRequestCanceledEvent(canceled, AT));
        kafka.send(APPEARANCE_TOPIC, "r:4", expiredEvent);
        kafka.send(APPEARANCE_TOPIC, "r:4", expiredEvent);
        awaitProcessed();

        assertThat(messages(guest)).containsExactly(
                "호스트님이 '가을 합방' 출연 요청을 취소했습니다.",
                "응답 기한이 지나 '겨울 합방' 출연 요청이 만료되었습니다.");
        assertThat(messages(host)).containsExactly("게스트님이 응답하지 않아 '겨울 합방' 출연 요청이 만료되었습니다.");
        assertThat(sourceRows("AppearanceRequestExpiredEvent", expired.requestId())).hasSize(2)
                .allSatisfy(row -> assertThat(row).containsEntry("target_type", "APPEARANCE_REQUEST")
                        .containsEntry("target_id", String.valueOf(expired.requestId())));
    }

    @Test
    void 지원_선정_미선정과_마감_일시_마감만_알림을_만든다() {
        long host = member("호스트");
        long guest = member("게스트");
        long recruitId = nextId();
        long appearanceId = nextId();
        AppearanceApplicationDto selected = application(recruitId, host, guest);
        AppearanceApplicationDto notSelected = application(recruitId, host, guest);
        AppearanceRecruitDto recruit = new AppearanceRecruitDto(recruitId, host, "가을 모집", AT);

        kafka.send(APPEARANCE_TOPIC, "a:1",
                new AppearanceApplicationSelectedEvent(selected, appearanceId, SCHEDULED, AT));
        kafka.send(APPEARANCE_TOPIC, "a:2", new AppearanceApplicationNotSelectedEvent(notSelected, SCHEDULED, AT));
        kafka.send(APPEARANCE_TOPIC, "m:1",
                new AppearanceRecruitClosedEvent(recruit, AppearanceRecruitCloseType.EARLY, AT));
        kafka.send(APPEARANCE_TOPIC, "m:2",
                new AppearanceRecruitClosedEvent(recruit, AppearanceRecruitCloseType.DEADLINE, AT));
        awaitProcessed();

        assertThat(rows(guest)).satisfiesExactly(
                row -> assertThat(row).containsEntry("kind", "APPEARANCE_APPLICATION_SELECTED")
                        .containsEntry("source_target_id", String.valueOf(selected.applicationId()))
                        .containsEntry("target_type", "APPEARANCE")
                        .containsEntry("target_id", String.valueOf(appearanceId))
                        .containsEntry("message", "호스트님의 '가을 모집' 모집에 선정되었습니다. 12월 1일 20:00 방송 예정입니다."),
                row -> assertThat(row).containsEntry("kind", "APPEARANCE_APPLICATION_NOT_SELECTED")
                        .containsEntry("source_target_id", String.valueOf(notSelected.applicationId()))
                        .containsEntry("target_type", "APPEARANCE_RECRUIT")
                        .containsEntry("target_id", String.valueOf(recruitId))
                        .containsEntry("message", "'가을 모집' 모집에 선정되지 않았습니다."));
        assertThat(rows(host)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("kind", "APPEARANCE_RECRUIT_CLOSED")
                .containsEntry("source_target_id", String.valueOf(recruitId))
                .containsEntry("target_type", "APPEARANCE_RECRUIT")
                .containsEntry("target_id", String.valueOf(recruitId))
                .containsEntry("message", "'가을 모집' 모집이 마감 일시가 되어 마감되었습니다."));
        assertThat(deadLetterKeys()).doesNotContain("m:1");
    }

    @Test
    void 일방_취소와_불참은_게스트에게_자동_확인만_호스트에게_만든다() {
        long host = member("호스트");
        long guest = member("게스트");
        AppearanceDto canceled = appearance(host, guest, "봄 합방");
        AppearanceDto absent = appearance(host, guest, "여름 합방");
        AppearanceDto autoConfirmed = appearance(host, guest, "가을 합방");
        AppearanceDto hostConfirmed = appearance(host, guest, "겨울 합방");

        kafka.send(APPEARANCE_TOPIC, "p:1", new AppearanceCanceledByHostEvent(canceled, 30_000L, AT));
        kafka.send(APPEARANCE_TOPIC, "p:2", new AppearanceAbsentEvent(absent, AT));
        kafka.send(APPEARANCE_TOPIC, "p:3",
                new AppearanceConfirmedEvent(hostConfirmed, AppearanceConfirmType.HOST, AT));
        kafka.send(APPEARANCE_TOPIC, "p:4",
                new AppearanceConfirmedEvent(autoConfirmed, AppearanceConfirmType.AUTO, AT));
        awaitProcessed();

        assertThat(rows(guest)).satisfiesExactly(
                row -> assertThat(row).containsEntry("kind", "APPEARANCE_CANCELED_BY_HOST")
                        .containsEntry("source_target_id", String.valueOf(canceled.appearanceId()))
                        .containsEntry("target_type", "APPEARANCE")
                        .containsEntry("target_id", String.valueOf(canceled.appearanceId()))
                        .containsEntry("message", "호스트님이 '봄 합방' 출연을 취소했습니다."),
                row -> assertThat(row).containsEntry("kind", "APPEARANCE_ABSENT")
                        .containsEntry("source_target_id", String.valueOf(absent.appearanceId()))
                        .containsEntry("target_type", "APPEARANCE")
                        .containsEntry("target_id", String.valueOf(absent.appearanceId()))
                        .containsEntry("message", "'여름 합방' 출연이 불참 처리되었습니다."));
        assertThat(rows(host)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("kind", "APPEARANCE_CONFIRMED")
                .containsEntry("source_target_id", String.valueOf(autoConfirmed.appearanceId()))
                .containsEntry("target_type", "APPEARANCE")
                .containsEntry("target_id", String.valueOf(autoConfirmed.appearanceId()))
                .containsEntry("message", "출연 확인 기간이 지나 '가을 합방' 출연이 자동 확인되었습니다."));
        assertThat(deadLetterKeys()).doesNotContain("p:3");
    }

    @Test
    void 파기된_호스트가_한_일방_취소는_파기_닉네임으로_만든다() {
        long host = nextId();
        users.save(NotificationUser.createMasked(host, Instant.parse("2026-11-10T00:00:00Z")));
        long guest = member("게스트");

        kafka.send(APPEARANCE_TOPIC, "p:masked",
                new AppearanceCanceledByHostEvent(appearance(host, guest, "파기 합방"), 30_000L, AT));
        awaitProcessed();

        assertThat(messages(guest)).containsExactly("탈퇴회원#" + host + "님이 '파기 합방' 출연을 취소했습니다.");
    }

    @Test
    void 모집_시작과_모집_전체_취소는_알림도_DLT도_없이_넘긴다() {
        long host = member("호스트");
        AppearanceRecruitDto recruit = new AppearanceRecruitDto(nextId(), host, "모집", AT.plusDays(3));

        kafka.send(APPEARANCE_TOPIC, "m:opened", new AppearanceRecruitOpenedEvent(recruit, AT));
        kafka.send(APPEARANCE_TOPIC, "m:canceled", new AppearanceRecruitCanceledEvent(recruit, AT));
        awaitProcessed();

        assertThat(rows(host)).isEmpty();
        assertThat(deadLetterKeys()).doesNotContain("m:opened", "m:canceled");
    }

    @Test
    void 알림_시작_시각보다_이른_출연_사건은_DLT_없이_건너뛴다() {
        long host = member("호스트");
        long guest = member("게스트");

        kafka.send(APPEARANCE_TOPIC, "old", new AppearanceAbsentEvent(appearance(host, guest, "옛 합방"),
                LocalDateTime.parse("2025-12-31T10:00:00")));
        awaitProcessed();

        assertThat(rows(guest)).isEmpty();
        assertThat(deadLetterKeys()).doesNotContain("old");
    }

    @Test
    void 성립한_출연_ID가_빠진_요청_수락은_DLT로_보낸다() {
        long host = member("호스트");
        long guest = member("게스트");

        kafka.send(APPEARANCE_TOPIC, "r:no-appearance",
                new AppearanceRequestAcceptedEvent(request(host, guest, "빈 합방"), null, AT));

        new MemberEventKafka(bootstrapServers, APPEARANCE_TOPIC).awaitDeadLetter(APPEARANCE_DLT, "r:no-appearance");
        assertThat(rows(host)).isEmpty();
    }

    private AppearanceRequestDto request(long host, long guest, String title) {
        return new AppearanceRequestDto(nextId(), host, guest, title, 100_000L, SCHEDULED, AT.plusDays(3));
    }

    private AppearanceApplicationDto application(long recruitId, long host, long guest) {
        return new AppearanceApplicationDto(nextId(), recruitId, "가을 모집", host, guest, AT.minusDays(1));
    }

    private AppearanceDto appearance(long host, long guest, String title) {
        return new AppearanceDto(nextId(), title, host, guest, SCHEDULED);
    }

    private long member(String nickname) {
        long id = nextId();
        users.save(NotificationUser.create(new UserDto(id, nickname, Role.INFLUENCER, UserStatus.ACTIVE, 1L)));
        return id;
    }

    private List<Map<String, Object>> rows(long receiver) {
        return jdbc.queryForList("select * from notification.notification where receiver_user_id = ? order by id",
                receiver);
    }

    private List<Map<String, Object>> sourceRows(String sourceEventType, long sourceTargetId) {
        return jdbc.queryForList(
                "select * from notification.notification where source_event_type = ? and source_target_id = ?",
                sourceEventType, String.valueOf(sourceTargetId));
    }

    private List<Object> messages(long receiver) {
        return rows(receiver).stream().map(row -> row.get("message")).toList();
    }

    private static Instant occurredAt(Map<String, Object> row) {
        return ((Timestamp) row.get("occurred_at")).toInstant();
    }

    /** 표식 불참 알림이 생기면 그 앞의 레코드도 처리가 끝났다(partition 하나) */
    private void awaitProcessed() {
        long host = member("표식호스트");
        long marker = member("표식");
        kafka.send(APPEARANCE_TOPIC, "marker", new AppearanceAbsentEvent(appearance(host, marker, "표식"), AT));
        await().atMost(TIMEOUT).until(() -> rows(marker).size() == 1);
    }

    private List<String> deadLetterKeys() {
        return new MemberEventKafka(bootstrapServers, APPEARANCE_DLT).deadLetterKeysNow(APPEARANCE_DLT);
    }

    private static long nextId() {
        return NEXT_ID.incrementAndGet();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic notificationAppearanceEventN7TestTopic() {
            return TopicBuilder.name(APPEARANCE_TOPIC).partitions(1).replicas(1).build();
        }
    }
}
