package com.hapbang.auxiliary.notification.in.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.testcontainers.kafka.KafkaContainer;

import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.billing.event.ChargeCompletedEvent;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * DLT 발행이 실패할 때의 계측과 offset(ND-159, kafka.md "재시도와 DLT"). test-spec NT-OBS-02, NT-ERR-04(통합).
 * <ul>
 *     <li>DLT가 없고 브로커 자동 생성이 꺼져 있으면 DLT 발행이 실패해 {@code dead_letter{result=failed}}가 오르고
 *     {@code published}는 오르지 않는다.</li>
 *     <li>복구가 실패한 레코드의 offset은 커밋되지 않아 같은 레코드를 다시 처리한다(실패 시도도 다시 센다).</li>
 *     <li>DLT를 만들면 다음 주기에 DLT로 넘어가 {@code published}가 오르고 offset이 넘어가 다음 레코드를 처리한다.</li>
 * </ul>
 * 공용 Kafka 컨테이너는 자동 생성이 켜져 있어, 자동 생성을 끈 이 클래스 전용 브로커를 띄운다(chat-app
 * {@code ChatMemberEventDeadLetterMissingTest}와 같은 방식). DLT 복제 수 3(브로커 1대)이라 앱의 DLT 선언이 실패해 DLT가 없다.
 */
@IntegrationTest
@DirtiesContext
class NotificationDeadLetterFailureMetricsTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String TOPIC = "billing.events.v1.dlt-failure-" + SUFFIX;
    private static final String GROUP = "notification-dlt-failure-" + SUFFIX;
    private static final String DLT = TOPIC + "." + GROUP + ".DLT";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Instant AT = Instant.parse("2026-11-14T01:00:00Z");

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
            .withLabel("hapbang.test", "tkt-944-dlt-failure");

    static {
        KAFKA.start();
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("원본 토픽을 만들지 못했습니다.", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add(NotificationKafkaConstants.BILLING_EVENTS_ENABLED, () -> "true");
        registry.add("hapbang.notification.alert.start-at", () -> "2026-01-01T00:00:00Z");
    }

    /** 재시도 없이 바로 복구하고, DLT 발행 대기는 최소값 1초다. DLT 복제 3이라 앱의 DLT 선언이 실패한다 */
    @TestBean(name = NotificationKafkaConfig.BILLING_EVENT_SETTINGS)
    private NotificationKafkaConsumerSettings billingEventSettings;

    static NotificationKafkaConsumerSettings billingEventSettings() {
        return new NotificationKafkaConsumerSettings(TOPIC, GROUP, DLT, 0, Duration.ofMillis(100),
                NotificationKafkaConstants.RETRY_MAX_INTERVAL, NotificationKafkaConstants.MIN_DEAD_LETTER_SEND_TIMEOUT,
                1, (short) 3);
    }

    @Autowired
    private NotificationUserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void DLT_발행이_실패하면_failed로_세고_offset을_넘기지_않으며_DLT를_만들면_published로_세고_넘어간다() throws Exception {
        assertThat(topics()).contains(TOPIC).doesNotContain(DLT);
        startListener();
        long unknown = 944_801L;
        long next = 944_802L;
        users.save(NotificationUser.create(new UserDto(next, "다음회원", Role.USER, UserStatus.ACTIVE, 1L)));

        try (AlertEventKafka kafka = new AlertEventKafka(KAFKA.getBootstrapServers())) {
            // 받는 사람 복제본이 없어 처리가 실패하고 DLT로 보내야 하는 레코드, 그 뒤의 정상 레코드
            kafka.send(TOPIC, "point-charge:unknown", new ChargeCompletedEvent(1L, unknown, 1L, 1L, AT));
            kafka.send(TOPIC, "point-charge:next", new ChargeCompletedEvent(2L, next, 1L, 1L, AT));
        }

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(deadLetter("failed")).isGreaterThanOrEqualTo(2.0);
            assertThat(failures()).isGreaterThanOrEqualTo(2.0);
        });
        assertThat(deadLetter("published")).isZero();
        assertThat(committedOffset()).as("실패한 레코드를 넘는 offset을 커밋하지 않는다")
                .satisfiesAnyOf(offset -> assertThat(offset).isNull(), offset -> assertThat(offset).isZero());
        assertThat(rows(next)).as("같은 partition의 다음 레코드가 막힌다").isZero();

        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic(DLT, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(rows(next)).isEqualTo(1));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(committedOffset()).isGreaterThan(1L));
        assertThat(deadLetter("published")).isEqualTo(1.0);
    }

    private void startListener() {
        MessageListenerContainer container = listenerRegistry.getListenerContainer(
                NotificationBillingEventListener.LISTENER_ID);
        if (!container.isRunning()) {
            container.start();
        }
    }

    private double deadLetter(String result) {
        Counter counter = meterRegistry.find("hapbang.notification.dead_letter").tag("topic", TOPIC)
                .tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private double failures() {
        return meterRegistry.find("hapbang.notification.consume.failures").tag("topic", TOPIC).counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    private int rows(long receiver) {
        return jdbc.queryForObject("select count(*) from notification.notification where receiver_user_id = ?",
                Integer.class, receiver);
    }

    private static Admin admin() {
        Properties config = new Properties();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        return Admin.create(config);
    }

    private static Set<String> topics() throws Exception {
        try (Admin admin = admin()) {
            return admin.listTopics().names().get(10, TimeUnit.SECONDS);
        }
    }

    private static Long committedOffset() throws Exception {
        try (Admin admin = admin()) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(GROUP)
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            OffsetAndMetadata committed = offsets.get(PARTITION);
            return committed == null ? null : committed.offset();
        }
    }
}
