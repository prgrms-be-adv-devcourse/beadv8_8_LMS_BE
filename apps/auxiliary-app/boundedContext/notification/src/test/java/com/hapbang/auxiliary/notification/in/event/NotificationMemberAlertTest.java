package com.hapbang.auxiliary.notification.in.event;

import static com.hapbang.auxiliary.notification.in.event.MemberEventKafka.TIMEOUT;
import static com.hapbang.auxiliary.notification.in.event.MemberEventKafka.userJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.hapbang.auxiliary.notification.app.NotificationWriter;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 가입 환영·승급 승인은 회원 복제본 리스너 안에서 복제본 반영과 함께 만든다(ND-080). 승급은 반영 전 역할로 판단하고(ND-016·ND-071),
 * 발생 시각은 레코드 timestamp다(ND-081). test-spec NT-GEN-06·07·16, NT-KAF-07, NT-USR-02
 */
@IntegrationTest
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, NotificationMemberAlertTest.Topics.class})
class NotificationMemberAlertTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String TOPIC = "member.events.v1.alert-test-" + SUFFIX;
    private static final String GROUP = "notification-member-alert-test-" + SUFFIX;
    private static final String DLT = TOPIC + "." + GROUP + ".DLT";
    private static final Instant START_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant RECORD_TIME = Instant.parse("2026-11-11T03:00:00Z");
    private static final AtomicLong NEXT_ID = new AtomicLong(700_000);

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

    /** 특정 회원의 알림 저장만 실패시켜 롤백·DLT를 본다. 그 밖 호출은 실제 저장 서비스로 간다 */
    @MockitoSpyBean
    private NotificationWriter writer;

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
    void 가입하면_복제본과_함께_가입_환영_알림을_한_번만_만들고_발생_시각은_레코드_시각이다() {
        long id = nextId();

        joined(id, Role.USER, 0L, RECORD_TIME);
        joined(id, Role.USER, 0L, RECORD_TIME);
        awaitProcessed();

        assertThat(users.findById(id)).isPresent();
        assertThat(rows(id)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "USER_JOINED")
                    .containsEntry("source_event_type", "UserJoinedEvent")
                    .containsEntry("source_target_id", String.valueOf(id))
                    .containsEntry("message", "합방에 오신 것을 환영합니다.");
            assertThat(row.get("target_type")).isNull();
            assertThat(((java.sql.Timestamp) row.get("occurred_at")).toInstant()).isEqualTo(RECORD_TIME);
        });
    }

    @Test
    void 관리자_가입과_알림_시작_시각_이전_가입은_복제본만_만든다() {
        long admin = nextId();
        long old = nextId();

        joined(admin, Role.ADMIN, 0L, RECORD_TIME);
        joined(old, Role.USER, 0L, START_AT.minusSeconds(1));
        awaitProcessed();

        assertThat(users.findById(admin)).isPresent();
        assertThat(users.findById(old)).isPresent();
        assertThat(rows(admin)).isEmpty();
        assertThat(rows(old)).isEmpty();
    }

    @Test
    void 인플루언서에서_인증된_인플루언서로_바뀌면_승급_승인을_버전마다_한_번_만든다() {
        long id = nextId();
        joined(id, Role.INFLUENCER, 1L, START_AT.minusSeconds(1));

        modified(id, Role.VERIFIED_INFLUENCER, 2L, RECORD_TIME);
        modified(id, Role.VERIFIED_INFLUENCER, 2L, RECORD_TIME);
        awaitProcessed();

        assertThat(rows(id)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("kind", "INFLUENCER_UPGRADE_REQUEST_APPROVED")
                .containsEntry("source_event_type", "UserModifiedEvent")
                .containsEntry("source_target_id", id + ":2")
                .containsEntry("message", "인증된 인플루언서로 승급되었습니다."));
    }

    @Test
    void 가입_환영_저장이_실패하면_복제본_반영도_롤백되고_재시도_뒤_DLT로_간다() {
        long id = nextId();
        doThrow(new IllegalStateException("알림 저장 실패(시험)")).when(writer)
                .save(argThat(draft -> draft != null && draft.receiverUserId() == id));

        joined(id, Role.USER, 0L, RECORD_TIME);

        kafka.awaitDeadLetter(DLT, id);
        assertThat(users.findById(id)).isEmpty();
        assertThat(rows(id)).isEmpty();
    }

    @Test
    void 레코드_시각이_알림_시작_시각보다_이른_승급은_복제본만_바꾸고_알림은_만들지_않는다() {
        long id = nextId();
        joined(id, Role.INFLUENCER, 1L, START_AT.minusSeconds(10));

        modified(id, Role.VERIFIED_INFLUENCER, 2L, START_AT.minusSeconds(1));
        awaitProcessed();

        assertThat(users.findById(id).orElseThrow().getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
        assertThat(rows(id)).isEmpty();
    }

    @Test
    void 역할이_그대로이거나_유저에서_인플루언서로_바뀌거나_늦게_온_옛_버전이면_승급_승인을_만들지_않는다() {
        long same = nextId();
        long channel = nextId();
        long stale = nextId();
        joined(same, Role.VERIFIED_INFLUENCER, 1L, START_AT.minusSeconds(1));
        joined(channel, Role.USER, 1L, START_AT.minusSeconds(1));
        joined(stale, Role.INFLUENCER, 3L, START_AT.minusSeconds(1));

        modified(same, Role.VERIFIED_INFLUENCER, 2L, RECORD_TIME);
        // 채널 인증 승인은 역할 비교로 만들지 않는다(ND-042)
        modified(channel, Role.INFLUENCER, 2L, RECORD_TIME);
        // 복제본은 이미 버전 3(인플루언서)인데 늦게 온 버전 2(인증된 인플루언서): 역할만 보면 승급 같지만 반영하지 않으므로 알림도 없다
        modified(stale, Role.VERIFIED_INFLUENCER, 2L, RECORD_TIME);
        awaitProcessed();

        assertThat(rows(same)).isEmpty();
        assertThat(rows(channel)).isEmpty();
        assertThat(rows(stale)).isEmpty();
    }

    @Test
    void 복제본이_없을_때_온_변경으로는_이전_역할을_알_수_없어_승급_승인을_만들지_않는다() {
        long id = nextId();

        modified(id, Role.VERIFIED_INFLUENCER, 5L, RECORD_TIME);
        awaitProcessed();

        assertThat(users.findById(id)).isPresent();
        assertThat(rows(id)).isEmpty();
    }

    private void joined(long id, Role role, Long version, Instant timestamp) {
        kafka.sendAt(UserJoinedEvent.class.getName(), id, userJson(id, "회원" + id, role, UserStatus.ACTIVE, version),
                timestamp);
    }

    private void modified(long id, Role role, Long version, Instant timestamp) {
        kafka.sendAt(UserModifiedEvent.class.getName(), id,
                userJson(id, "회원" + id, role, UserStatus.ACTIVE, version), timestamp);
    }

    private List<Map<String, Object>> rows(long receiver) {
        return jdbc.queryForList("select * from notification.notification where receiver_user_id = ? order by id",
                receiver);
    }

    /** 표식 회원의 가입 환영이 생기면 앞서 보낸 레코드도 처리가 끝났다(partition 하나) */
    private void awaitProcessed() {
        long marker = nextId();
        joined(marker, Role.USER, 0L, RECORD_TIME);
        await().atMost(TIMEOUT).until(() -> rows(marker).size() == 1);
    }

    private static long nextId() {
        return NEXT_ID.incrementAndGet();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic notificationMemberAlertTestTopic() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }
    }
}
