package com.hapbang.auxiliary.notification.in.event;

import static com.hapbang.auxiliary.notification.in.event.MemberEventKafka.TIMEOUT;
import static com.hapbang.auxiliary.notification.in.event.MemberEventKafka.userJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.InfluencerChannelApprovedEvent;
import com.hapbang.shared.member.event.InfluencerChannelRejectedEvent;
import com.hapbang.shared.member.event.InfluencerUpgradeRequestRejectedEvent;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * N7 회원 3종(#915)을 실제 Kafka로 받아 회원 복제본 리스너 안에서 알림을 만드는지 본다(ND-118~ND-121). test-spec NT-GEN-22,
 * NT-GEN-10·15(회원 3종), NT-GEN-14 (2)
 * <p>
 * 토픽·그룹은 이 클래스 전용(UUID), partition 하나라 마지막에 보낸 표식 이벤트가 처리되면 앞선 레코드도 끝난 것이다.
 */
@IntegrationTest
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, NotificationMemberReviewAlertTest.Topics.class})
class NotificationMemberReviewAlertTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String TOPIC = "member.events.v1.review-test-" + SUFFIX;
    private static final String GROUP = "notification-member-review-test-" + SUFFIX;
    private static final String DLT = TOPIC + "." + GROUP + ".DLT";
    private static final Instant START_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant AFTER_START = Instant.parse("2026-11-12T02:00:00Z");
    /** 다른 통합 시험과 겹치지 않는 회원·대상 ID 범위(같은 DB를 함께 쓴다) */
    private static final AtomicLong NEXT_ID = new AtomicLong(2_500_000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(NotificationKafkaConstants.MEMBER_EVENTS_ENABLED, () -> "true");
        registry.add("hapbang.notification.alert.start-at", START_AT::toString);
    }

    @TestBean(name = NotificationKafkaConfig.MEMBER_EVENT_SETTINGS)
    private NotificationKafkaConsumerSettings memberEventSettings;

    static NotificationKafkaConsumerSettings memberEventSettings() {
        return new NotificationKafkaConsumerSettings(TOPIC, GROUP, DLT, 1, Duration.ofMillis(100),
                NotificationKafkaConstants.RETRY_MAX_INTERVAL, NotificationKafkaConstants.DEAD_LETTER_SEND_TIMEOUT, 1,
                NotificationKafkaConstants.DEAD_LETTER_REPLICAS);
    }

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Autowired
    private NotificationUserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private MemberEventKafka kafka;

    @BeforeEach
    void setUp() {
        kafka = new MemberEventKafka(bootstrapServers, TOPIC);
    }

    @AfterEach
    void tearDown() {
        kafka.close();
    }

    @Test
    void 채널_인증_승인은_한_번만_만들고_이동_대상이_없다() {
        long user = member(Role.USER, UserStatus.ACTIVE);
        long channel = nextId();

        approved(user, channel, 1, true, AFTER_START);
        approved(user, channel, 1, true, AFTER_START);
        awaitProcessed();

        assertThat(rows(user)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "INFLUENCER_CHANNEL_APPROVED")
                    .containsEntry("source_event_type", "InfluencerChannelApprovedEvent")
                    .containsEntry("source_target_id", channel + ":1")
                    .containsEntry("target_type", null)
                    .containsEntry("target_id", null)
                    .containsEntry("message", "채널 인증이 승인되었습니다.");
            assertThat(occurredAt(row)).isEqualTo(AFTER_START);
        });
    }

    @Test
    void 이미_인플루언서인_회원의_채널_승인도_만든다() {
        long user = member(Role.INFLUENCER, UserStatus.ACTIVE);

        approved(user, nextId(), 2, false, AFTER_START);
        awaitProcessed();

        assertThat(rows(user)).extracting(row -> row.get("kind")).containsExactly("INFLUENCER_CHANNEL_APPROVED");
    }

    @Test
    void 같은_채널의_반려는_회차마다_한_번_만들고_같은_회차의_승인과_반려는_둘_다_만든다() {
        long user = member(Role.USER, UserStatus.ACTIVE);
        long channel = nextId();
        Instant second = AFTER_START.plusSeconds(60);
        Instant third = AFTER_START.plusSeconds(120);

        channelRejected(user, channel, 1, "팔로워 수가 기준에 못 미칩니다.", AFTER_START);
        // 같은 회차 반려의 재전송은 한 번만 만든다
        channelRejected(user, channel, 1, "팔로워 수가 기준에 못 미칩니다.", AFTER_START);
        channelRejected(user, channel, 2, "방송 기록이 부족합니다.", second);
        // 회원 앱은 같은 회차에 둘 다 내지 않지만, 종류가 다르면 같은 원본 대상 ID여도 서로 막지 않는다
        approved(user, channel, 2, false, third);
        awaitProcessed();

        List<Map<String, Object>> rows = rows(user);
        assertThat(rows).extracting(row -> row.get("kind")).containsExactly("INFLUENCER_CHANNEL_REJECTED",
                "INFLUENCER_CHANNEL_REJECTED", "INFLUENCER_CHANNEL_APPROVED");
        assertThat(rows).extracting(row -> row.get("source_target_id")).containsExactly(channel + ":1",
                channel + ":2", channel + ":2");
        assertThat(rows).extracting(row -> row.get("message")).containsExactly(
                "채널 인증이 반려되었습니다. 사유: 팔로워 수가 기준에 못 미칩니다.",
                "채널 인증이 반려되었습니다. 사유: 방송 기록이 부족합니다.",
                "채널 인증이 승인되었습니다.");
        assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("target_type", null)
                .containsEntry("target_id", null));
        assertThat(rows).extracting(NotificationMemberReviewAlertTest::occurredAt)
                .containsExactly(AFTER_START, second, third);
    }

    @Test
    void 승급_반려는_승급_신청_ID로_한_번만_만든다() {
        long user = member(Role.INFLUENCER, UserStatus.ACTIVE);
        long request = nextId();

        upgradeRejected(user, request, "강의 계획이 부족합니다.", AFTER_START);
        upgradeRejected(user, request, "강의 계획이 부족합니다.", AFTER_START);
        awaitProcessed();

        assertThat(rows(user)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "INFLUENCER_UPGRADE_REQUEST_REJECTED")
                    .containsEntry("source_event_type", "InfluencerUpgradeRequestRejectedEvent")
                    .containsEntry("source_target_id", String.valueOf(request))
                    .containsEntry("target_type", null)
                    .containsEntry("target_id", null)
                    .containsEntry("message", "인증된 인플루언서 승급 신청이 반려되었습니다. 사유: 강의 계획이 부족합니다.");
            assertThat(occurredAt(row)).isEqualTo(AFTER_START);
        });
    }

    @Test
    void 알림_시작_시각보다_이른_사건과_탈퇴_정지_회원은_알림_없이_건너뛴다() {
        long user = member(Role.USER, UserStatus.ACTIVE);
        long suspended = member(Role.USER, UserStatus.SUSPENDED);
        long withdrawn = member(Role.INFLUENCER, UserStatus.WITHDRAWN);

        approved(user, nextId(), 1, true, START_AT.minusSeconds(1));
        channelRejected(suspended, nextId(), 1, "사유", AFTER_START);
        upgradeRejected(withdrawn, nextId(), "사유", AFTER_START);
        awaitProcessed();

        assertThat(rows(user)).isEmpty();
        assertThat(rows(suspended)).isEmpty();
        assertThat(rows(withdrawn)).isEmpty();
        assertThat(kafka.deadLetterKeysNow(DLT)).doesNotContain(String.valueOf(user), String.valueOf(suspended),
                String.valueOf(withdrawn));
    }

    @Test
    void 필수_값이_빠진_회원_심사_이벤트는_DLT로_보내고_저장하지_않는다() {
        long noSequence = member(Role.USER, UserStatus.ACTIVE);
        long blankReason = member(Role.INFLUENCER, UserStatus.ACTIVE);

        kafka.send(InfluencerChannelApprovedEvent.class.getName(), noSequence,
                "{\"userId\":" + noSequence + ",\"channelId\":" + nextId()
                        + ",\"reviewSequence\":null,\"autoApproved\":true,\"approvedAt\":\"" + AFTER_START + "\"}");
        kafka.send(InfluencerUpgradeRequestRejectedEvent.class.getName(), blankReason,
                "{\"userId\":" + blankReason + ",\"upgradeRequestId\":" + nextId()
                        + ",\"rejectionReason\":\" \",\"rejectedAt\":\"" + AFTER_START + "\"}");

        kafka.awaitDeadLetter(DLT, noSequence);
        kafka.awaitDeadLetter(DLT, blankReason);
        awaitProcessed();
        assertThat(rows(noSequence)).isEmpty();
        assertThat(rows(blankReason)).isEmpty();
    }

    private long member(Role role, UserStatus status) {
        long id = nextId();
        users.save(NotificationUser.create(new UserDto(id, "회원" + id, role, status, 1L)));
        return id;
    }

    private void approved(long user, long channel, int sequence, boolean auto, Instant at) {
        kafka.send(InfluencerChannelApprovedEvent.class.getName(), user,
                "{\"userId\":" + user + ",\"channelId\":" + channel + ",\"reviewSequence\":" + sequence
                        + ",\"autoApproved\":" + auto + ",\"approvedAt\":\"" + at + "\"}");
    }

    private void channelRejected(long user, long channel, int sequence, String reason, Instant at) {
        kafka.send(InfluencerChannelRejectedEvent.class.getName(), user,
                "{\"userId\":" + user + ",\"channelId\":" + channel + ",\"reviewSequence\":" + sequence
                        + ",\"rejectionReason\":\"" + reason + "\",\"rejectedAt\":\"" + at + "\"}");
    }

    private void upgradeRejected(long user, long request, String reason, Instant at) {
        kafka.send(InfluencerUpgradeRequestRejectedEvent.class.getName(), user,
                "{\"userId\":" + user + ",\"upgradeRequestId\":" + request + ",\"rejectionReason\":\"" + reason
                        + "\",\"rejectedAt\":\"" + at + "\"}");
    }

    private List<Map<String, Object>> rows(long receiver) {
        return jdbc.queryForList("select * from notification.notification where receiver_user_id = ? order by id",
                receiver);
    }

    private static Instant occurredAt(Map<String, Object> row) {
        return ((Timestamp) row.get("occurred_at")).toInstant();
    }

    /** 표식 회원의 가입 환영이 생기면 앞서 보낸 레코드도 처리가 끝났다(partition 하나) */
    private void awaitProcessed() {
        long marker = nextId();
        kafka.sendAt(UserJoinedEvent.class.getName(), marker,
                userJson(marker, "회원" + marker, Role.USER, UserStatus.ACTIVE, 0L), AFTER_START);
        await().atMost(TIMEOUT).until(() -> rows(marker).size() == 1);
    }

    private static long nextId() {
        return NEXT_ID.incrementAndGet();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic notificationMemberReviewAlertTestTopic() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }
    }
}
