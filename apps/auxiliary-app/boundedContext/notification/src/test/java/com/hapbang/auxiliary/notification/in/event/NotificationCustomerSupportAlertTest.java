package com.hapbang.auxiliary.notification.in.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.customersupport.event.InquiryAnsweredEvent;
import com.hapbang.shared.customersupport.event.LectureObjectionFileRejectedEvent;
import com.hapbang.shared.customersupport.event.LectureObjectionResolutionRequestedEvent;
import com.hapbang.shared.customersupport.event.LectureObjectionWithdrawalRequestedEvent;
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
 * N7 고객센터 2종(#918)을 실제 Kafka로 받아 알림을 만드는지 본다(ND-122~ND-126). test-spec NT-GEN-23, NT-KAF-10,
 * NT-GEN-10·15(고객센터 2종)
 * <p>
 * 토픽·그룹은 이 클래스 전용(UUID), partition 하나라 마지막에 보낸 표식 이벤트가 처리되면 앞선 레코드도 끝난 것이다.
 */
@IntegrationTest
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, NotificationCustomerSupportAlertTest.Topics.class})
class NotificationCustomerSupportAlertTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String TOPIC = "customersupport.events.v1.test-" + SUFFIX;
    private static final String GROUP = "notification-customersupport-test-" + SUFFIX;
    private static final String DLT = TOPIC + "." + GROUP + ".DLT";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Instant START_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant AFTER_START = Instant.parse("2026-11-12T03:00:00Z");
    /** 다른 통합 시험과 겹치지 않는 회원·대상 ID 범위 */
    private static final AtomicLong NEXT_ID = new AtomicLong(2_300_000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(NotificationKafkaConstants.CUSTOMERSUPPORT_EVENTS_ENABLED, () -> "true");
        registry.add("hapbang.notification.alert.start-at", START_AT::toString);
    }

    @TestBean(name = NotificationKafkaConfig.CUSTOMERSUPPORT_EVENT_SETTINGS)
    private NotificationKafkaConsumerSettings customerSupportEventSettings;

    static NotificationKafkaConsumerSettings customerSupportEventSettings() {
        return new NotificationKafkaConsumerSettings(TOPIC, GROUP, DLT, 1,
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
        // 알림 소비자는 첫 Backfill 뒤에 시작한다(ND-078·ND-122). 이 시험은 Backfill을 끄고 직접 시작한다
        MessageListenerContainer container = listenerRegistry.getListenerContainer(
                NotificationCustomerSupportEventListener.LISTENER_ID);
        if (!container.isRunning()) {
            container.start();
        }
    }

    @AfterEach
    void tearDown() {
        kafka.close();
    }

    @Test
    void 문의_답변_도착은_답변마다_한_번만_만든다() {
        long user = member(UserStatus.ACTIVE);
        long inquiryId = nextId();
        InquiryAnsweredEvent first = new InquiryAnsweredEvent(inquiryId, nextId(), user, "환불 문의",
                "SERVICE_COMPLAINT", AFTER_START);
        InquiryAnsweredEvent second = new InquiryAnsweredEvent(inquiryId, nextId(), user, "환불 문의",
                "SERVICE_COMPLAINT", AFTER_START.plusSeconds(60));

        kafka.send(TOPIC, "inquiry:" + inquiryId, first);
        kafka.send(TOPIC, "inquiry:" + inquiryId, first);
        kafka.send(TOPIC, "inquiry:" + inquiryId, second);
        awaitProcessed();

        List<Map<String, Object>> rows = rows(user);
        assertThat(rows).extracting(row -> row.get("source_target_id"))
                .containsExactly(String.valueOf(first.replyId()), String.valueOf(second.replyId()));
        assertThat(rows).allSatisfy(row -> assertThat(row)
                .containsEntry("kind", "INQUIRY_ANSWERED")
                .containsEntry("source_event_type", "InquiryAnsweredEvent")
                .containsEntry("target_type", "INQUIRY")
                .containsEntry("target_id", String.valueOf(inquiryId))
                .containsEntry("message", "'환불 문의' 문의에 답변이 등록되었습니다."));
        assertThat(rows).extracting(NotificationCustomerSupportAlertTest::occurredAt)
                .containsExactly(AFTER_START, AFTER_START.plusSeconds(60));
    }

    @Test
    void 이의_제기_접수_불가는_구매자에게_한_번만_만든다() {
        long buyer = member(UserStatus.ACTIVE);
        LectureObjectionFileRejectedEvent event = new LectureObjectionFileRejectedEvent(nextId(), nextId(), buyer,
                AFTER_START);

        kafka.send(TOPIC, "inquiry:" + event.inquiryId(), event);
        kafka.send(TOPIC, "inquiry:" + event.inquiryId(), event);
        awaitProcessed();

        assertThat(rows(buyer)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "LECTURE_OBJECTION_FILE_REJECTED")
                    .containsEntry("source_event_type", "LectureObjectionFileRejectedEvent")
                    .containsEntry("source_target_id", String.valueOf(event.inquiryId()))
                    .containsEntry("target_type", "INQUIRY")
                    .containsEntry("target_id", String.valueOf(event.inquiryId()))
                    .containsEntry("message", "강의 내용 다름 이의 제기를 접수하지 못했습니다. 문의 내용을 확인해 주세요.");
            assertThat(occurredAt(row)).isEqualTo(AFTER_START);
        });
    }

    @Test
    void 알림_시작_시각보다_이른_사건과_탈퇴_정지_회원과_이의_제기_명령은_알림_없이_건너뛴다() {
        long user = member(UserStatus.ACTIVE);
        long suspended = member(UserStatus.SUSPENDED);
        long withdrawn = member(UserStatus.WITHDRAWN);
        long inquiryId = nextId();

        kafka.send(TOPIC, "old", new InquiryAnsweredEvent(nextId(), nextId(), user, "t", "OTHER",
                Instant.parse("2025-12-31T23:59:59Z")));
        kafka.send(TOPIC, "suspended", new LectureObjectionFileRejectedEvent(nextId(), nextId(), suspended,
                AFTER_START));
        kafka.send(TOPIC, "withdrawn", new InquiryAnsweredEvent(nextId(), nextId(), withdrawn, "t", "OTHER",
                AFTER_START));
        // 고객센터가 강의에 보내는 이의 제기 명령 2종은 알림이 아니다(ND-122)
        kafka.send(TOPIC, "resolution", new LectureObjectionResolutionRequestedEvent(UUID.randomUUID().toString(),
                inquiryId, nextId(), true, nextId()));
        kafka.send(TOPIC, "withdrawal", new LectureObjectionWithdrawalRequestedEvent(UUID.randomUUID().toString(),
                inquiryId, nextId(), user));
        awaitProcessed();

        assertThat(rows(user)).isEmpty();
        assertThat(rows(suspended)).isEmpty();
        assertThat(rows(withdrawn)).isEmpty();
        assertThat(new MemberEventKafka(bootstrapServers, DLT).deadLetterKeysNow(DLT))
                .doesNotContain("old", "suspended", "withdrawn", "resolution", "withdrawal");
    }

    @Test
    void 필수_값이_빠진_고객센터_이벤트는_DLT로_보낸다() {
        long user = member(UserStatus.ACTIVE);

        kafka.send(TOPIC, "inquiry:no-title", new InquiryAnsweredEvent(nextId(), nextId(), user, null, "OTHER",
                AFTER_START));
        kafka.send(TOPIC, "inquiry:no-time", new LectureObjectionFileRejectedEvent(nextId(), nextId(), user, null));
        // 공백뿐인 제목도 필수 값 없음이다(ND-127)
        kafka.send(TOPIC, "inquiry:blank-title", new InquiryAnsweredEvent(nextId(), nextId(), user, "  ", "OTHER",
                AFTER_START));

        MemberEventKafka dlt = new MemberEventKafka(bootstrapServers, TOPIC);
        dlt.awaitDeadLetter(DLT, "inquiry:no-title");
        dlt.awaitDeadLetter(DLT, "inquiry:no-time");
        dlt.awaitDeadLetter(DLT, "inquiry:blank-title");
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

    /** 표식 접수 불가 알림이 생기면 그 앞의 레코드도 처리가 끝났다(partition 하나) */
    private void awaitProcessed() {
        long marker = member(UserStatus.ACTIVE);
        kafka.send(TOPIC, "marker", new LectureObjectionFileRejectedEvent(nextId(), nextId(), marker, AFTER_START));
        await().atMost(TIMEOUT).until(() -> rows(marker).size() == 1);
    }

    private static long nextId() {
        return NEXT_ID.incrementAndGet();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic notificationCustomerSupportAlertTestTopic() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }
    }
}
