package com.hapbang.auxiliary.notification.in.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.auxiliary.notification.app.NotificationOutcomeRecorder;
import com.hapbang.auxiliary.notification.app.NotificationRealtimeRelay;
import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.billing.domain.PayoutType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.event.ChargeCompletedEvent;
import com.hapbang.shared.billing.event.PayoutCompletedEvent;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Kafka 소비 계측(ND-159~ND-161)을 실제 Kafka·PostgreSQL로 본다. test-spec NT-OBS-01·03·04·05·06(일부)·10.
 * <ul>
 *     <li>처리 실패 시도는 재시도마다, 역직렬화 실패도 같은 지표로 센다. DLT 전송 성공 수는 DLT 토픽에 실제로 들어간 레코드 수와 같다.</li>
 *     <li>저장 결과는 commit된 시도만 결과별로 센다. 롤백 뒤 재시도로 commit되면 한 번만 센다.</li>
 *     <li>commit 뒤 집계가 실패해도 처리는 한 번에 끝나고 offset이 넘어간다.</li>
 *     <li>소비 지연은 성공한 레코드마다 한 번 잰다.</li>
 * </ul>
 * 토픽·그룹은 이 클래스 전용(UUID), partition 하나라 마지막에 보낸 표식 이벤트가 처리되면 앞선 레코드도 끝난 것이다.
 * 지표는 컨텍스트 안에서 누적되므로 시험마다 앞뒤 차이로 본다.
 */
@IntegrationTest
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, NotificationKafkaMetricsIntegrationTest.Topics.class})
class NotificationKafkaMetricsIntegrationTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String TOPIC = "billing.events.v1.metrics-" + SUFFIX;
    private static final String GROUP = "notification-billing-metrics-" + SUFFIX;
    private static final String DLT = TOPIC + "." + GROUP + ".DLT";
    private static final int RETRIES = 1;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Instant AT = Instant.parse("2026-11-14T01:00:00Z");
    private static final AtomicLong NEXT_ID = new AtomicLong(944_500);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(NotificationKafkaConstants.BILLING_EVENTS_ENABLED, () -> "true");
        registry.add("hapbang.notification.alert.start-at", () -> "2026-01-01T00:00:00Z");
    }

    @TestBean(name = NotificationKafkaConfig.BILLING_EVENT_SETTINGS)
    private NotificationKafkaConsumerSettings billingEventSettings;

    static NotificationKafkaConsumerSettings billingEventSettings() {
        return new NotificationKafkaConsumerSettings(TOPIC, GROUP, DLT, RETRIES, Duration.ofMillis(100),
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

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    @Qualifier("notificationBillingEventConsumerFactory")
    private ConsumerFactory<String, Object> billingConsumerFactory;

    @MockitoSpyBean
    private NotificationRealtimeRelay relay;

    @MockitoSpyBean
    private NotificationOutcomeRecorder outcomeRecorder;

    private AlertEventKafka kafka;

    @BeforeEach
    void setUp() {
        kafka = new AlertEventKafka(bootstrapServers);
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

    // 저장 결과 (NT-OBS-04)

    @Test
    void commit된_저장_결과를_created_duplicate_skipped_receiver_updated로_센다() {
        Map<String, Double> before = outcomes();
        long active = member(UserStatus.ACTIVE);
        long suspended = member(UserStatus.SUSPENDED);
        long chargeId = nextId();

        kafka.send(TOPIC, "point-charge:" + chargeId, new ChargeCompletedEvent(chargeId, active, 1L, 1L, AT));
        kafka.send(TOPIC, "point-charge:" + chargeId, new ChargeCompletedEvent(chargeId, active, 1L, 1L, AT));
        kafka.send(TOPIC, "point-charge:s", new ChargeCompletedEvent(nextId(), suspended, 1L, 1L, AT));
        kafka.send(TOPIC, "settlement:1", new PayoutCompletedEvent(nextId(), active, PayoutType.NORMAL,
                SettlementSourceType.LECTURE_ORDER_ITEM, nextId(), 1_000L, AT));
        kafka.send(TOPIC, "settlement:2", new PayoutCompletedEvent(nextId(), active, PayoutType.NORMAL,
                SettlementSourceType.LECTURE_ORDER_ITEM, nextId(), 2_000L, AT.plusSeconds(60)));
        awaitProcessed();

        Map<String, Double> after = outcomes();
        // 충전 1 + 첫 묶음 1 + 표식 1
        assertThat(after.get("created") - before.get("created")).isEqualTo(3.0);
        assertThat(after.get("duplicate") - before.get("duplicate")).isEqualTo(1.0);
        assertThat(after.get("skipped_receiver") - before.get("skipped_receiver")).isEqualTo(1.0);
        assertThat(after.get("updated") - before.get("updated")).isEqualTo(1.0);
    }

    @Test
    void 저장_뒤_같은_트랜잭션이_실패해_롤백되고_재시도로_commit되면_한_번만_센다() {
        long active = member(UserStatus.ACTIVE);
        AtomicBoolean failed = new AtomicBoolean();
        // 저장한 뒤 같은 트랜잭션 안에서 처음 한 번만 실패시킨다(실시간 전달 예약 단계)
        doAnswer(invocation -> {
            if (failed.compareAndSet(false, true)) {
                throw new TransientDataAccessResourceException("저장 뒤 일시 실패(시험)");
            }
            return invocation.callRealMethod();
        }).when(relay).publishAfterCommit(anyLong(), eq(active));
        double createdBefore = outcomes().get("created");
        double failuresBefore = failures("TransientDataAccessResourceException");

        kafka.send(TOPIC, "point-charge:retry", new ChargeCompletedEvent(nextId(), active, 1L, 1L, AT));
        awaitProcessed();

        assertThat(rows(active)).hasSize(1);
        assertThat(failed).isTrue();
        // 충전 1 + 표식 1. 롤백된 첫 시도는 세지 않는다
        assertThat(outcomes().get("created") - createdBefore).isEqualTo(2.0);
        assertThat(failures("TransientDataAccessResourceException") - failuresBefore).isEqualTo(1.0);
    }

    // commit 뒤 집계 실패 (NT-OBS-05)

    @Test
    void commit_뒤_집계가_실패해도_처리는_한_번에_끝나고_다음_레코드로_넘어간다() {
        long active = member(UserStatus.ACTIVE);
        doThrow(new IllegalStateException("지표 저장소 고장(시험)"))
                .when(outcomeRecorder).count(eq(TOPIC), any());
        double failuresBefore = totalFailures();

        kafka.send(TOPIC, "point-charge:broken-metric", new ChargeCompletedEvent(nextId(), active, 1L, 1L, AT));
        awaitProcessed();

        assertThat(rows(active)).hasSize(1);
        assertThat(totalFailures()).isEqualTo(failuresBefore);
        assertThat(deadLetterKeys()).doesNotContain("point-charge:broken-metric");
    }

    // 실패 시도와 DLT (NT-OBS-01·03)

    @Test
    void 계속_실패하면_처음과_재시도를_모두_세고_DLT_전송_성공은_DLT에_실제로_들어간_수와_같다() {
        long unknown = nextId();
        double failuresBefore = failures("NotificationException");
        double publishedBefore = deadLetter("published");
        int dltBefore = deadLetterKeys().size();

        kafka.send(TOPIC, "point-charge:unknown-" + unknown, new ChargeCompletedEvent(nextId(), unknown, 1L, 1L, AT));

        new MemberEventKafka(bootstrapServers, TOPIC).awaitDeadLetter(DLT, "point-charge:unknown-" + unknown);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(failures("NotificationException") - failuresBefore).isEqualTo(1.0 + RETRIES);
            assertThat(deadLetter("published") - publishedBefore).isEqualTo(deadLetterKeys().size() - dltBefore);
        });
        assertThat(deadLetter("published") - publishedBefore).isEqualTo(1.0);
        assertThat(deadLetter("failed")).isZero();
    }

    @Test
    void 깨진_본문과_신뢰_밖_타입의_역직렬화_실패도_실패_시도로_세고_재시도_없이_DLT로_보낸다() {
        double failuresBefore = failures("DeserializationException");
        double publishedBefore = deadLetter("published");

        kafka.sendRaw(TOPIC, "broken", ChargeCompletedEvent.class.getName(), "{not-json");
        kafka.sendRaw(TOPIC, "untrusted", "com.hapbang.shared.member.dto.UserDto", "{\"id\":1}");

        MemberEventKafka dlt = new MemberEventKafka(bootstrapServers, TOPIC);
        dlt.awaitDeadLetter(DLT, "broken");
        dlt.awaitDeadLetter(DLT, "untrusted");
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(failures("DeserializationException") - failuresBefore).isEqualTo(2.0);
            assertThat(deadLetter("published") - publishedBefore).isEqualTo(2.0);
        });
    }

    // 소비 지연 (NT-OBS-06)

    @Test
    void 성공한_레코드마다_소비_지연을_한_번_잰다() {
        long active = member(UserStatus.ACTIVE);
        long before = latencyCount();

        kafka.send(TOPIC, "point-charge:latency", new ChargeCompletedEvent(nextId(), active, 1L, 1L, AT));
        awaitProcessed();

        // 충전 1 + 표식 1
        await().atMost(TIMEOUT).until(() -> latencyCount() - before == 2);
    }

    // Kafka client 지표 (NT-OBS-10)

    @Test
    void 알림_소비자_팩토리는_Kafka_client_지표_listener를_가진다() {
        assertThat(billingConsumerFactory.getListeners())
                .anySatisfy(listener -> assertThat(listener).isInstanceOf(MicrometerConsumerListener.class));
    }

    private Map<String, Double> outcomes() {
        return Map.of("created", outcome("created"), "updated", outcome("updated"), "duplicate",
                outcome("duplicate"), "skipped_receiver", outcome("skipped_receiver"));
    }

    private double outcome(String result) {
        Counter counter = meterRegistry.find("hapbang.notification.consume.outcomes").tag("topic", TOPIC)
                .tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private double failures(String exception) {
        Counter counter = meterRegistry.find("hapbang.notification.consume.failures").tag("topic", TOPIC)
                .tag("exception", exception).counter();
        return counter == null ? 0 : counter.count();
    }

    private double totalFailures() {
        return meterRegistry.find("hapbang.notification.consume.failures").tag("topic", TOPIC).counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    private double deadLetter(String result) {
        Counter counter = meterRegistry.find("hapbang.notification.dead_letter").tag("topic", TOPIC)
                .tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private long latencyCount() {
        Timer timer = meterRegistry.find("hapbang.notification.consume.latency").tag("topic", TOPIC).timer();
        return timer == null ? 0 : timer.count();
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

    /** 표식 충전 알림이 생기면 그 앞의 레코드도 처리가 끝났다(partition 하나) */
    private void awaitProcessed() {
        long marker = member(UserStatus.ACTIVE);
        kafka.send(TOPIC, "marker", new ChargeCompletedEvent(nextId(), marker, 1L, 1L, AT));
        await().atMost(TIMEOUT).until(() -> rows(marker).size() == 1);
    }

    private List<String> deadLetterKeys() {
        return new MemberEventKafka(bootstrapServers, DLT).deadLetterKeysNow(DLT);
    }

    private static long nextId() {
        return NEXT_ID.incrementAndGet();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic notificationBillingMetricsTestTopic() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }
    }
}
