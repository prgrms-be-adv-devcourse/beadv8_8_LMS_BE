package com.hapbang.auxiliary.notification.in.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.billing.event.ChargeCompletedEvent;
import com.hapbang.shared.billing.event.ChargeFailedEvent;
import com.hapbang.shared.billing.event.ExchangeApprovedEvent;
import com.hapbang.shared.billing.event.RefundFailedEvent;
import com.hapbang.shared.billing.event.RefundManualReviewRequiredEvent;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * N7 지갑 4종(#907)을 실제 Kafka로 받아 알림을 만드는지 본다(ND-105~ND-108). test-spec NT-GEN-19, NT-GEN-10·15(지갑 4종)
 * <p>
 * 토픽·그룹은 이 클래스 전용(UUID), partition 하나라 마지막에 보낸 표식 이벤트가 처리되면 앞선 레코드도 끝난 것이다.
 */
@IntegrationTest
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, NotificationWalletAlertTest.Topics.class})
class NotificationWalletAlertTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String BILLING_TOPIC = "billing.events.v1.test-" + SUFFIX;
    private static final String BILLING_GROUP = "notification-billing-test-" + SUFFIX;
    private static final String BILLING_DLT = BILLING_TOPIC + "." + BILLING_GROUP + ".DLT";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Instant START_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant AFTER_START = Instant.parse("2026-11-12T01:00:00Z");
    /** 다른 통합 시험과 겹치지 않는 회원·대상 ID 범위 */
    private static final AtomicLong NEXT_ID = new AtomicLong(2_100_000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(NotificationKafkaConstants.BILLING_EVENTS_ENABLED, () -> "true");
        registry.add("hapbang.notification.alert.start-at", START_AT::toString);
    }

    @TestBean(name = NotificationKafkaConfig.BILLING_EVENT_SETTINGS)
    private NotificationKafkaConsumerSettings billingEventSettings;

    static NotificationKafkaConsumerSettings billingEventSettings() {
        return new NotificationKafkaConsumerSettings(BILLING_TOPIC, BILLING_GROUP, BILLING_DLT, 1,
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
                NotificationBillingEventListener.LISTENER_ID);
        if (!container.isRunning()) {
            container.start();
        }
    }

    @AfterEach
    void tearDown() {
        kafka.close();
    }

    @Test
    void 충전_실패는_충전한_회원에게_한_번만_만든다() {
        long user = member(UserStatus.ACTIVE);
        ChargeFailedEvent event = new ChargeFailedEvent(nextId(), user, 12_000L, 12_000L, AFTER_START);

        kafka.send(BILLING_TOPIC, "point-charge:" + event.chargeId(), event);
        kafka.send(BILLING_TOPIC, "point-charge:" + event.chargeId(), event);
        awaitProcessed();

        assertThat(rows(user)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "CHARGE_FAILED")
                    .containsEntry("source_event_type", "ChargeFailedEvent")
                    .containsEntry("source_target_id", String.valueOf(event.chargeId()))
                    .containsEntry("target_type", "CHARGE")
                    .containsEntry("target_id", String.valueOf(event.chargeId()))
                    .containsEntry("message", "12,000 HP 충전에 실패했습니다.");
            assertThat(occurredAt(row)).isEqualTo(AFTER_START);
        });
    }

    @Test
    void 같은_환불_요청의_운영자_확인_필요와_환불_실패는_둘_다_만든다() {
        long user = member(UserStatus.ACTIVE);
        long refundRequestId = nextId();
        Instant failedAt = AFTER_START.plusSeconds(60);

        kafka.send(BILLING_TOPIC, "point-refund-request:" + refundRequestId,
                new RefundManualReviewRequiredEvent(refundRequestId, user, 3_500L, AFTER_START));
        kafka.send(BILLING_TOPIC, "point-refund-request:" + refundRequestId,
                new RefundFailedEvent(refundRequestId, user, 3_500L, failedAt));
        awaitProcessed();

        List<Map<String, Object>> rows = rows(user);
        assertThat(rows).extracting(row -> row.get("kind"))
                .containsExactly("REFUND_MANUAL_REVIEW_REQUIRED", "REFUND_FAILED");
        assertThat(rows).allSatisfy(row -> assertThat(row)
                .containsEntry("source_target_id", String.valueOf(refundRequestId))
                .containsEntry("target_type", "REFUND_REQUEST")
                .containsEntry("target_id", String.valueOf(refundRequestId)));
        assertThat(rows).extracting(row -> row.get("message")).containsExactly(
                "3,500 HP 환불은 운영자 확인이 필요해 처리가 늦어지고 있습니다.",
                "3,500 HP 환불에 실패했습니다. 신청한 HP는 지갑으로 돌아왔습니다.");
        assertThat(rows).extracting(NotificationWalletAlertTest::occurredAt).containsExactly(AFTER_START, failedAt);
    }

    @Test
    void 환전_승인은_송금_예정일을_넣어_만든다() {
        long user = member(UserStatus.ACTIVE);
        ExchangeApprovedEvent event = new ExchangeApprovedEvent(nextId(), user, 50_000L,
                Instant.parse("2026-12-10T01:00:00Z"), AFTER_START);

        kafka.send(BILLING_TOPIC, "exchange:" + event.exchangeId(), event);
        awaitProcessed();

        assertThat(rows(user)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "EXCHANGE_APPROVED")
                    .containsEntry("source_event_type", "ExchangeApprovedEvent")
                    .containsEntry("source_target_id", String.valueOf(event.exchangeId()))
                    .containsEntry("target_type", "EXCHANGE")
                    .containsEntry("target_id", String.valueOf(event.exchangeId()))
                    .containsEntry("message", "50,000 HP 환전 신청이 승인되었습니다. 12월 10일에 송금됩니다.");
            assertThat(occurredAt(row)).isEqualTo(AFTER_START);
        });
    }

    @Test
    void 알림_시작_시각보다_이른_사건과_탈퇴_정지_회원은_알림_없이_건너뛴다() {
        long user = member(UserStatus.ACTIVE);
        long suspended = member(UserStatus.SUSPENDED);
        long withdrawn = member(UserStatus.WITHDRAWN);

        kafka.send(BILLING_TOPIC, "old", new ChargeFailedEvent(nextId(), user, 1L, 1L,
                Instant.parse("2025-12-31T23:59:59Z")));
        kafka.send(BILLING_TOPIC, "suspended", new ExchangeApprovedEvent(nextId(), suspended, 1L,
                Instant.parse("2026-12-10T01:00:00Z"), AFTER_START));
        kafka.send(BILLING_TOPIC, "withdrawn", new RefundFailedEvent(nextId(), withdrawn, 1L, AFTER_START));
        awaitProcessed();

        assertThat(rows(user)).isEmpty();
        assertThat(rows(suspended)).isEmpty();
        assertThat(rows(withdrawn)).isEmpty();
        assertThat(new MemberEventKafka(bootstrapServers, BILLING_DLT).deadLetterKeysNow(BILLING_DLT))
                .doesNotContain("old", "suspended", "withdrawn");
    }

    @Test
    void 필수_값이_빠진_지갑_이벤트는_DLT로_보낸다() {
        long user = member(UserStatus.ACTIVE);

        kafka.send(BILLING_TOPIC, "exchange:no-schedule", new ExchangeApprovedEvent(nextId(), user, 1L, null,
                AFTER_START));

        new MemberEventKafka(bootstrapServers, BILLING_TOPIC).awaitDeadLetter(BILLING_DLT, "exchange:no-schedule");
        assertThat(rows(user)).isEmpty();
    }

    private long member(UserStatus status) {
        long id = nextId();
        users.save(NotificationUser.create(new UserDto(id, "회원", Role.USER, status, 1L)));
        return id;
    }

    private List<Map<String, Object>> rows(long receiver) {
        return jdbc.queryForList("select * from notification.notification where receiver_user_id = ? order by id",
                receiver);
    }

    private static Instant occurredAt(Map<String, Object> row) {
        return ((Timestamp) row.get("occurred_at")).toInstant();
    }

    /** 표식 충전 완료 알림이 생기면 그 앞의 레코드도 처리가 끝났다(partition 하나) */
    private void awaitProcessed() {
        long marker = member(UserStatus.ACTIVE);
        kafka.send(BILLING_TOPIC, "marker", new ChargeCompletedEvent(nextId(), marker, 1L, 1L, AFTER_START));
        await().atMost(TIMEOUT).until(() -> rows(marker).size() == 1);
    }

    private static long nextId() {
        return NEXT_ID.incrementAndGet();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic notificationWalletAlertTestTopic() {
            return TopicBuilder.name(BILLING_TOPIC).partitions(1).replicas(1).build();
        }
    }
}
