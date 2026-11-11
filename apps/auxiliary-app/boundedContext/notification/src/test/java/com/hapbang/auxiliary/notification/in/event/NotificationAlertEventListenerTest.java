package com.hapbang.auxiliary.notification.in.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.shared.appearance.dto.AppearanceParticipantRole;
import com.hapbang.shared.appearance.event.AppearanceApplicationSubmittedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;
import com.hapbang.shared.billing.domain.PayoutType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.event.ChargeCompletedEvent;
import com.hapbang.shared.billing.event.ExchangePayoutFailedEvent;
import com.hapbang.shared.billing.event.PayoutCompletedEvent;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;
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
 * 출연·지갑 이벤트를 실제 Kafka로 받아 알림을 만드는지 본다(ND-069~ND-076·ND-081).
 * test-spec NT-GEN-01·03·07·15, NT-IDEM-05, NT-KAF-04·07, NT-USR-04·05
 * <p>
 * 토픽·그룹은 이 클래스 전용(UUID), partition 하나라 마지막에 보낸 표식 이벤트가 처리되면 앞선 레코드도 끝난 것이다.
 */
@IntegrationTest
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, NotificationAlertEventListenerTest.Topics.class})
class NotificationAlertEventListenerTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String APPEARANCE_TOPIC = "appearance.events.v1.test-" + SUFFIX;
    private static final String BILLING_TOPIC = "billing.events.v1.test-" + SUFFIX;
    private static final String APPEARANCE_GROUP = "notification-appearance-test-" + SUFFIX;
    private static final String BILLING_GROUP = "notification-billing-test-" + SUFFIX;
    private static final String APPEARANCE_DLT = APPEARANCE_TOPIC + "." + APPEARANCE_GROUP + ".DLT";
    private static final String BILLING_DLT = BILLING_TOPIC + "." + BILLING_GROUP + ".DLT";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** 이 클래스의 알림 시작 시각(ND-076) */
    private static final Instant START_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant AFTER_START = Instant.parse("2026-11-11T01:00:00Z");
    private static final AtomicLong NEXT_ID = new AtomicLong(500_000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(NotificationKafkaConstants.APPEARANCE_EVENTS_ENABLED, () -> "true");
        registry.add(NotificationKafkaConstants.BILLING_EVENTS_ENABLED, () -> "true");
        registry.add("hapbang.notification.alert.start-at", START_AT::toString);
    }

    @TestBean(name = NotificationKafkaConfig.APPEARANCE_EVENT_SETTINGS)
    private NotificationKafkaConsumerSettings appearanceEventSettings;

    @TestBean(name = NotificationKafkaConfig.BILLING_EVENT_SETTINGS)
    private NotificationKafkaConsumerSettings billingEventSettings;

    static NotificationKafkaConsumerSettings appearanceEventSettings() {
        return settings(APPEARANCE_TOPIC, APPEARANCE_GROUP, APPEARANCE_DLT);
    }

    static NotificationKafkaConsumerSettings billingEventSettings() {
        return settings(BILLING_TOPIC, BILLING_GROUP, BILLING_DLT);
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
                NotificationBillingEventListener.LISTENER_ID)) {
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

    @Test
    void 충전_완료_이벤트로_충전한_회원에게_알림을_한_번만_만든다() {
        long user = member(Role.USER, UserStatus.ACTIVE);
        ChargeCompletedEvent event = new ChargeCompletedEvent(nextId(), user, 12_000L, 12_000L, AFTER_START);

        kafka.send(BILLING_TOPIC, "point-charge:" + event.chargeId(), event);
        kafka.send(BILLING_TOPIC, "point-charge:" + event.chargeId(), event);
        awaitBillingProcessed();

        assertThat(rows(user)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "CHARGE_COMPLETED")
                    .containsEntry("source_event_type", "ChargeCompletedEvent")
                    .containsEntry("source_target_id", String.valueOf(event.chargeId()))
                    .containsEntry("target_type", "CHARGE")
                    .containsEntry("target_id", String.valueOf(event.chargeId()))
                    .containsEntry("message", "12,000 HP 충전이 완료되었습니다.");
            assertThat(((java.sql.Timestamp) row.get("occurred_at")).toInstant()).isEqualTo(AFTER_START);
        });
    }

    @Test
    void 출연_원천_지급은_만들고_강의_원천_지급은_DLT_없이_건너뛴다() {
        long guest = member(Role.INFLUENCER, UserStatus.ACTIVE);
        long appearanceId = nextId();

        kafka.send(BILLING_TOPIC, "settlement:1", new PayoutCompletedEvent(nextId(), guest, PayoutType.NORMAL,
                SettlementSourceType.APPEARANCE_REQUEST, appearanceId, 50_000L, AFTER_START));
        kafka.send(BILLING_TOPIC, "settlement:2", new PayoutCompletedEvent(nextId(), guest, PayoutType.NORMAL,
                SettlementSourceType.LECTURE_ORDER_ITEM, nextId(), 9_000L, AFTER_START));
        awaitBillingProcessed();

        assertThat(rows(guest)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("kind", "PAYOUT_COMPLETED")
                .containsEntry("target_type", "APPEARANCE")
                .containsEntry("target_id", String.valueOf(appearanceId)));
        assertThat(deadLetterKeys(BILLING_DLT)).doesNotContain("settlement:2");
    }

    @Test
    void 같은_환전의_송금_실패는_시도마다_한_건씩_만든다() {
        long user = member(Role.INFLUENCER, UserStatus.ACTIVE);
        long exchangeId = nextId();

        kafka.send(BILLING_TOPIC, "exchange:" + exchangeId,
                new ExchangePayoutFailedEvent(exchangeId, nextId(), 1, user, 28_500L, AFTER_START));
        kafka.send(BILLING_TOPIC, "exchange:" + exchangeId,
                new ExchangePayoutFailedEvent(exchangeId, nextId(), 2, user, 28_500L, AFTER_START.plusSeconds(60)));
        awaitBillingProcessed();

        assertThat(rows(user)).hasSize(2).allSatisfy(row -> assertThat(row)
                .containsEntry("kind", "EXCHANGE_PAYOUT_FAILED")
                .containsEntry("target_id", String.valueOf(exchangeId)));
    }

    @Test
    void 알림_시작_시각보다_이른_사건은_DLT_없이_건너뛰고_같은_시각은_만든다() {
        long user = member(Role.USER, UserStatus.ACTIVE);
        long before = nextId();

        kafka.send(BILLING_TOPIC, "point-charge:" + before,
                new ChargeCompletedEvent(before, user, 1_000L, 1_000L, START_AT.minusMillis(1)));
        kafka.send(BILLING_TOPIC, "point-charge:x",
                new ChargeCompletedEvent(nextId(), user, 2_000L, 2_000L, START_AT));
        awaitBillingProcessed();

        assertThat(rows(user)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("message", "2,000 HP 충전이 완료되었습니다."));
        assertThat(deadLetterKeys(BILLING_DLT)).doesNotContain("point-charge:" + before);
    }

    @Test
    void 정지된_회원과_관리자에게는_만들지_않고_DLT로도_보내지_않는다() {
        long suspended = member(Role.INFLUENCER, UserStatus.SUSPENDED);
        long admin = member(Role.ADMIN, UserStatus.ACTIVE);

        kafka.send(BILLING_TOPIC, "point-charge:s", new ChargeCompletedEvent(nextId(), suspended, 1L, 1L, AFTER_START));
        kafka.send(BILLING_TOPIC, "point-charge:a", new ChargeCompletedEvent(nextId(), admin, 1L, 1L, AFTER_START));
        awaitBillingProcessed();

        assertThat(rows(suspended)).isEmpty();
        assertThat(rows(admin)).isEmpty();
        assertThat(deadLetterKeys(BILLING_DLT)).doesNotContain("point-charge:s", "point-charge:a");
    }

    @Test
    void 받을_수_없는_회원이면_언급_회원_복제본이_없어도_DLT_없이_건너뛴다() {
        long withdrawnGuest = member(Role.INFLUENCER, UserStatus.WITHDRAWN);
        long missingHost = nextId();

        kafka.send(APPEARANCE_TOPIC, "appearance-request:withdrawn",
                new AppearanceRequestSentEvent(new AppearanceRequestDto(nextId(), missingHost, withdrawnGuest, "제목",
                        1L, LocalDateTime.parse("2026-11-20T20:00:00"), LocalDateTime.parse("2026-11-13T18:30:00"))),
                AFTER_START);
        awaitAppearanceProcessed();

        assertThat(rows(withdrawnGuest)).isEmpty();
        assertThat(deadLetterKeys(APPEARANCE_DLT)).doesNotContain("appearance-request:withdrawn");
    }

    @Test
    void 탈퇴한_회원에게는_만들지_않는다() {
        long withdrawn = member(Role.USER, UserStatus.WITHDRAWN);

        kafka.send(BILLING_TOPIC, "point-charge:w", new ChargeCompletedEvent(nextId(), withdrawn, 1L, 1L, AFTER_START));
        awaitBillingProcessed();

        assertThat(rows(withdrawn)).isEmpty();
        assertThat(deadLetterKeys(BILLING_DLT)).doesNotContain("point-charge:w");
    }

    @Test
    void 받는_사람_복제본이_없으면_재시도_뒤_DLT로_보낸다() {
        long unknown = nextId();

        kafka.send(BILLING_TOPIC, "point-charge:unknown-" + unknown,
                new ChargeCompletedEvent(nextId(), unknown, 1L, 1L, AFTER_START));

        new MemberEventKafka(bootstrapServers, BILLING_TOPIC)
                .awaitDeadLetter(BILLING_DLT, "point-charge:unknown-" + unknown);
        assertThat(rows(unknown)).isEmpty();
    }

    @Test
    void 출연_요청_도착은_게스트에게_호스트_닉네임으로_만들고_발생_시각은_레코드_시각이다() {
        long host = member(Role.INFLUENCER, UserStatus.ACTIVE, "호스트님");
        long guest = member(Role.INFLUENCER, UserStatus.ACTIVE);
        long requestId = nextId();
        Instant recordTime = Instant.parse("2026-11-11T02:30:00Z");

        kafka.send(APPEARANCE_TOPIC, "appearance-request:" + requestId,
                new AppearanceRequestSentEvent(new AppearanceRequestDto(requestId, host, guest, "겨울 합방", 50_000L,
                        LocalDateTime.parse("2026-11-20T20:00:00"), LocalDateTime.parse("2026-11-13T18:30:00"))),
                recordTime);
        awaitAppearanceProcessed();

        assertThat(rows(guest)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "APPEARANCE_REQUEST_SENT")
                    .containsEntry("target_type", "APPEARANCE_REQUEST")
                    .containsEntry("target_id", String.valueOf(requestId))
                    .containsEntry("message",
                            "호스트님님이 '겨울 합방' 출연을 요청했습니다. 11월 13일 18:30까지 응답해 주세요.");
            assertThat(((java.sql.Timestamp) row.get("occurred_at")).toInstant()).isEqualTo(recordTime);
        });
    }

    @Test
    void 레코드_시각이_알림_시작_시각보다_이른_출연_요청은_건너뛴다() {
        long host = member(Role.INFLUENCER, UserStatus.ACTIVE);
        long guest = member(Role.INFLUENCER, UserStatus.ACTIVE);

        kafka.send(APPEARANCE_TOPIC, "appearance-request:old",
                new AppearanceRequestSentEvent(new AppearanceRequestDto(nextId(), host, guest, "옛 요청", 1L,
                        LocalDateTime.parse("2026-11-20T20:00:00"), LocalDateTime.parse("2026-11-13T18:30:00"))),
                START_AT.minusSeconds(1));
        awaitAppearanceProcessed();

        assertThat(rows(guest)).isEmpty();
    }

    @Test
    void 모집_지원_도착은_호스트에게_만들고_알림_대상이_아닌_출연_이벤트는_DLT_없이_건너뛴다() {
        long host = member(Role.INFLUENCER, UserStatus.ACTIVE);
        long guest = member(Role.INFLUENCER, UserStatus.ACTIVE, "게스트님");
        long recruitId = nextId();

        kafka.send(APPEARANCE_TOPIC, "appearance-application:1", new AppearanceApplicationSubmittedEvent(
                new AppearanceApplicationDto(nextId(), recruitId, "봄 모집", host, guest,
                        LocalDateTime.parse("2026-11-11T09:15:00"))));
        kafka.send(APPEARANCE_TOPIC, "appearance-review:1", new AppearanceReviewPublishedEvent(nextId(), nextId(),
                host, guest, AppearanceParticipantRole.GUEST, 5, LocalDateTime.parse("2026-11-11T10:00:00")));
        awaitAppearanceProcessed();

        assertThat(rows(host)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("kind", "APPEARANCE_APPLICATION_SUBMITTED")
                .containsEntry("target_type", "APPEARANCE_RECRUIT")
                .containsEntry("target_id", String.valueOf(recruitId))
                .containsEntry("message", "게스트님님이 '봄 모집' 모집에 지원했습니다."));
        assertThat(deadLetterKeys(APPEARANCE_DLT)).doesNotContain("appearance-review:1");
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

    /** 표식 충전 알림이 생기면 그 앞의 레코드도 처리가 끝났다(partition 하나) */
    private void awaitBillingProcessed() {
        long marker = member(Role.USER, UserStatus.ACTIVE);
        kafka.send(BILLING_TOPIC, "marker", new ChargeCompletedEvent(nextId(), marker, 1L, 1L, AFTER_START));
        await().atMost(TIMEOUT).until(() -> rows(marker).size() == 1);
    }

    private void awaitAppearanceProcessed() {
        long host = member(Role.INFLUENCER, UserStatus.ACTIVE);
        long marker = member(Role.INFLUENCER, UserStatus.ACTIVE);
        kafka.send(APPEARANCE_TOPIC, "marker", new AppearanceApplicationSubmittedEvent(
                new AppearanceApplicationDto(nextId(), nextId(), "표식", marker, host,
                        LocalDateTime.parse("2026-11-11T09:00:00"))));
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
        NewTopic notificationAppearanceEventTestTopic() {
            return TopicBuilder.name(APPEARANCE_TOPIC).partitions(1).replicas(1).build();
        }

        @Bean
        NewTopic notificationBillingEventTestTopic() {
            return TopicBuilder.name(BILLING_TOPIC).partitions(1).replicas(1).build();
        }
    }
}
