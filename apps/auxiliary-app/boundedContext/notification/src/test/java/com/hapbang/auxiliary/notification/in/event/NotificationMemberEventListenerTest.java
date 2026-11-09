package com.hapbang.auxiliary.notification.in.event;

import static com.hapbang.auxiliary.notification.in.event.MemberEventKafka.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserLoggedOutEvent;
import com.hapbang.shared.member.event.UserMaskedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

/**
 * 회원 이벤트를 실제 Kafka(Testcontainers)로 받아 알림 회원 복제본으로 만드는지 본다(ND-018, test-spec NT-USR-01·02, ND-010).
 * <p>
 * 이 클래스만 쓰는 토픽·그룹을 UUID로 만들고 테스트마다 새 회원 ID를 쓴다. 원본 토픽은 partition 하나라 마지막에 보낸 표식 회원이
 * 반영되면 앞서 보낸 레코드도 처리가 끝난 것이다.
 */
@IntegrationTest
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, NotificationMemberEventListenerTest.Topics.class})
class NotificationMemberEventListenerTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String TOPIC = "member.events.v1.test-" + SUFFIX;
    private static final String GROUP = "notification-test-" + SUFFIX;
    private static final String DLT = TOPIC + "." + GROUP + ".DLT";
    private static final AtomicLong NEXT_ID = new AtomicLong(200_000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(NotificationKafkaConstants.MEMBER_EVENTS_ENABLED, () -> "true");
    }

    /** 이 클래스 전용 토픽·그룹. 원본이 partition 1개라 DLT도 1개다(운영은 원본과 같은 3) */
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
    private NotificationUserRepository repository;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

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
    void 가입_이벤트를_받으면_알림_회원_복제본을_만든다() {
        long id = nextId();

        joined(id, "게스트", Role.INFLUENCER, UserStatus.ACTIVE, 0L);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(repository.findById(id)).hasValueSatisfying(user -> {
            assertThat(user.getNickname()).isEqualTo("게스트");
            assertThat(user.getRole()).isEqualTo(Role.INFLUENCER);
            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(user.getMemberVersion()).isZero();
        }));
    }

    @Test
    void 같은_가입을_두_번_받아도_한_명이고_변경은_더_큰_버전만_반영한다() {
        long id = nextId();

        joined(id, "가입", Role.USER, UserStatus.ACTIVE, 1L);
        joined(id, "가입", Role.USER, UserStatus.ACTIVE, 1L);
        modified(id, "최신", Role.INFLUENCER, UserStatus.ACTIVE, 3L);
        modified(id, "옛것", Role.USER, UserStatus.WITHDRAWN, 2L);
        awaitProcessed();

        assertThat(repository.findById(id)).hasValueSatisfying(user -> {
            assertThat(user.getNickname()).isEqualTo("최신");
            assertThat(user.getRole()).isEqualTo(Role.INFLUENCER);
            assertThat(user.isWithdrawn()).isFalse();
            assertThat(user.getMemberVersion()).isEqualTo(3L);
        });
    }

    @Test
    void 가입보다_먼저_온_변경으로_복제본을_만들고_늦게_온_가입은_건너뛴다() {
        long id = nextId();

        // 72시간보다 먼저 가입해 가입 이벤트가 토픽에 없는 회원, 또는 가입·변경 순서가 바뀐 경우(ND-049)
        modified(id, "변경후", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 2L);
        joined(id, "가입때", Role.USER, UserStatus.ACTIVE, 0L);
        awaitProcessed();

        assertThat(repository.findById(id)).hasValueSatisfying(user -> {
            assertThat(user.getNickname()).isEqualTo("변경후");
            assertThat(user.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
            assertThat(user.getMemberVersion()).isEqualTo(2L);
        });
        assertThat(kafka.deadLetterKeysNow(DLT)).doesNotContain(String.valueOf(id));
    }

    @Test
    void 처리하지_않는_회원_이벤트와_모르는_종류는_건너뛰고_다음_레코드를_처리한다() {
        long id = nextId();

        // N1은 파기(UserMaskedEvent)·로그아웃을 쓰지 않는다. 파기 때 저장 문구 갱신은 N2(ND-036, ND-046)
        kafka.send(UserMaskedEvent.class.getName(), id, "{\"userId\":" + id + ",\"maskedAt\":\"2026-11-09T00:00:00Z\"}");
        kafka.send(UserLoggedOutEvent.class.getName(), id,
                "{\"userId\":" + id + ",\"loggedOutAt\":\"2026-11-09T00:00:00Z\"}");
        // 회원 쪽이 먼저 배포한, 이 앱 클래스패스에 아직 없는 새 종류
        kafka.send("com.hapbang.shared.member.event.UserFutureEvent", id, "{\"userId\":" + id + "}");
        awaitProcessed();

        assertThat(repository.findById(id)).isEmpty();
        // 오류가 아니라 건너뛴 것이다: DLT로 가지 않는다
        assertThat(kafka.deadLetterKeysNow(DLT)).doesNotContain(String.valueOf(id));
    }

    @Test
    void 운영과_같은_방식으로_만든_리스너_컨테이너가_설정한_그룹으로_뜬다() {
        MessageListenerContainer container = listenerRegistry.getListenerContainer(
                NotificationMemberEventListener.LISTENER_ID);

        assertThat(container).isNotNull();
        assertThat(container.getGroupId()).isEqualTo(GROUP);
        assertThat(container.getContainerProperties().getTopics()).containsExactly(TOPIC);
    }

    private void joined(long id, String nickname, Role role, UserStatus status, Long version) {
        kafka.send(UserJoinedEvent.class.getName(), id, userJson(id, nickname, role, status, version));
    }

    private void modified(long id, String nickname, Role role, UserStatus status, Long version) {
        kafka.send(UserModifiedEvent.class.getName(), id, userJson(id, nickname, role, status, version));
    }

    /** partition이 하나라, 마지막에 보낸 표식 회원의 가입이 반영되면 앞서 보낸 레코드의 처리도 끝났다 */
    private void awaitProcessed() {
        long marker = nextId();
        joined(marker, "표식", Role.USER, UserStatus.ACTIVE, 0L);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(repository.findById(marker)).isPresent());
    }

    private static long nextId() {
        return NEXT_ID.incrementAndGet();
    }

    /** 이 클래스 전용 원본 토픽. DLT는 알림 설정(NotificationKafkaTopicConfig)이 만든다 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic notificationMemberEventTestTopic() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }
    }
}
