package com.hapbang.auxiliary.notification.in.event;

import static com.hapbang.auxiliary.notification.in.event.MemberEventKafka.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.auxiliary.notification.app.NotificationUserSyncService;
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

/**
 * 처리하지 못한 회원 이벤트가 그룹별 DLT로 가는 방식(kafka.md "재시도와 DLT", ND-010, test-spec NT-ERR-01~03).
 * 실패를 셋으로 나눠 본다.
 * <ul>
 *     <li>역직렬화 실패(깨진 본문, 신뢰 패키지 밖 타입): 리스너를 부르지 않고 재시도 없이 바로 DLT</li>
 *     <li>검증 실패(복제할 수 없는 회원 정보): 한 번만 처리하고 재시도 없이 DLT</li>
 *     <li>업무 처리 실패(DB 일시 장애가 계속됨): 처음 1번 + 재시도한 뒤 DLT</li>
 * </ul>
 * 복제본이 없을 때 온 변경은 실패가 아니라 그 정보로 복제본을 만든다(ND-049).
 * 원본 토픽은 운영과 같은 partition 3개이고, DLT는 원본과 같은 partition 번호로 받는다.
 */
@IntegrationTest
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, NotificationMemberEventDeadLetterTest.Topics.class})
class NotificationMemberEventDeadLetterTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String TOPIC = "member.events.v1.dlt-test-" + SUFFIX;
    private static final String GROUP = "notification-dlt-test-" + SUFFIX;
    private static final String DLT = TOPIC + "." + GROUP + ".DLT";
    private static final int PARTITIONS = 3;
    private static final int RETRIES = 2;
    private static final AtomicLong NEXT_ID = new AtomicLong(300_000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(NotificationKafkaConstants.MEMBER_EVENTS_ENABLED, () -> "true");
    }

    /** 이 클래스 전용 토픽·그룹. 재시도는 2회·첫 간격 100ms로 줄이고, DLT partition 수는 운영처럼 원본과 같은 3이다 */
    @TestBean(name = NotificationKafkaConfig.MEMBER_EVENT_SETTINGS)
    private NotificationKafkaConsumerSettings memberEventSettings;

    static NotificationKafkaConsumerSettings memberEventSettings() {
        return new NotificationKafkaConsumerSettings(TOPIC, GROUP, DLT, RETRIES, Duration.ofMillis(100),
                NotificationKafkaConstants.RETRY_MAX_INTERVAL, NotificationKafkaConstants.DEAD_LETTER_SEND_TIMEOUT,
                PARTITIONS, NotificationKafkaConstants.DEAD_LETTER_REPLICAS);
    }

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @MockitoSpyBean
    private NotificationUserSyncService syncService;

    @Autowired
    private NotificationUserRepository repository;

    @Autowired
    private KafkaAdmin kafkaAdmin;

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
    void 깨진_본문은_리스너를_부르지_않고_재시도_없이_같은_partition_번호의_DLT로_보낸다() {
        long id = nextId();

        kafka.send(UserJoinedEvent.class.getName(), id, "{not-json", 2);

        ConsumerRecord<String, byte[]> deadLetter = kafka.awaitDeadLetter(DLT, id);
        assertThat(deadLetter.partition()).isEqualTo(2);
        assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_PARTITION)).isNotNull();
        assertThat(exceptionClasses(deadLetter)).contains("DeserializationException");
        // 읽지 못한 원본을 바이트 그대로 싣고 타입 헤더를 남겨, 고쳐 다시 넣을 수 있다(kafka.md runbook)
        assertThat(new String(deadLetter.value(), StandardCharsets.UTF_8)).isEqualTo("{not-json");
        assertThat(header(deadLetter, "__TypeId__")).isEqualTo(UserJoinedEvent.class.getName());
        verify(syncService, never()).syncJoined(argThat(user -> user != null && user.id() == id));
        assertThat(repository.findById(id)).isEmpty();
    }

    @Test
    void 신뢰_패키지_밖의_타입은_역직렬화_실패로_재시도_없이_DLT로_보낸다() {
        long id = nextId();

        // 회원 DTO 패키지는 이벤트 패키지가 아니라 믿지 않는다
        kafka.send("com.hapbang.shared.member.dto.UserDto", id,
                "{\"id\":" + id + ",\"nickname\":\"x\",\"role\":\"ADMIN\",\"status\":\"ACTIVE\",\"version\":1}", 0);

        ConsumerRecord<String, byte[]> deadLetter = kafka.awaitDeadLetter(DLT, id);
        assertThat(deadLetter.partition()).isZero();
        assertThat(exceptionClasses(deadLetter)).contains("DeserializationException");
        assertThat(repository.findById(id)).isEmpty();
    }

    @Test
    void 복제할_수_없는_회원_정보는_한_번만_처리하고_재시도_없이_DLT로_보낸다() {
        long id = nextId();

        kafka.send(UserJoinedEvent.class.getName(), id,
                "{\"user\":{\"id\":" + id + ",\"nickname\":null,\"role\":\"USER\",\"status\":\"ACTIVE\",\"version\":1}}",
                1);

        ConsumerRecord<String, byte[]> deadLetter = kafka.awaitDeadLetter(DLT, id);
        assertThat(deadLetter.partition()).isEqualTo(1);
        assertThat(exceptionClasses(deadLetter)).contains("RejectedEventException");
        verify(syncService, times(1)).syncJoined(argThat(user -> user != null && user.id() == id));
        assertThat(repository.findById(id)).isEmpty();
    }

    @Test
    void 처리가_계속_실패하면_재시도한_뒤_같은_partition_번호의_DLT로_보내고_다음_레코드를_처리한다() {
        long id = nextId();
        // 업무 처리 실패(예: DB 일시 장애)가 재시도 동안 계속되는 경우
        doThrow(new TransientDataAccessResourceException("DB 일시 장애(테스트)"))
                .when(syncService).syncModified(argThat(user -> user != null && user.id() == id));

        kafka.send(UserModifiedEvent.class.getName(), id,
                userJson(id, "게스트", Role.INFLUENCER, UserStatus.ACTIVE, 1L), 2);

        ConsumerRecord<String, byte[]> deadLetter = kafka.awaitDeadLetter(DLT, id);
        assertThat(deadLetter.partition()).isEqualTo(2);
        assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(TOPIC);
        assertThat(exceptionClasses(deadLetter)).contains("TransientDataAccessResourceException");
        // 처음 1번 + 재시도 2번
        verify(syncService, times(1 + RETRIES)).syncModified(argThat(user -> user != null && user.id() == id));
        assertThat(repository.findById(id)).isEmpty();

        // DLT로 넘긴 뒤 offset이 넘어가 같은 partition의 다음 레코드(같은 회원의 가입)를 처리한다
        kafka.send(UserJoinedEvent.class.getName(), id,
                userJson(id, "게스트", Role.INFLUENCER, UserStatus.ACTIVE, 0L), 2);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(repository.findById(id)).isPresent());
    }

    @Test
    void 복제본이_없을_때_온_변경은_DLT로_보내지_않고_그_정보로_복제본을_만든다() {
        long id = nextId();

        kafka.send(UserModifiedEvent.class.getName(), id,
                userJson(id, "오래된회원", Role.INFLUENCER, UserStatus.ACTIVE, 5L), 0);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(repository.findById(id)).hasValueSatisfying(user -> {
            assertThat(user.getNickname()).isEqualTo("오래된회원");
            assertThat(user.getMemberVersion()).isEqualTo(5L);
        }));
        // 처음 한 번에 처리했다(재시도 없음)
        verify(syncService, times(1)).syncModified(argThat(user -> user != null && user.id() == id));
        assertThat(kafka.deadLetterKeysNow(DLT)).doesNotContain(String.valueOf(id));
    }

    @Test
    void 알림_모듈이_원본과_같은_partition_수로_그룹별_DLT를_선언한다() {
        Map<String, TopicDescription> topics = kafkaAdmin.describeTopics(DLT);

        assertThat(topics.get(DLT).partitions()).hasSize(PARTITIONS);
        assertThat(kafkaAdmin.describeTopics(TOPIC).get(TOPIC).partitions()).hasSize(PARTITIONS);
    }

    private static long nextId() {
        return NEXT_ID.incrementAndGet();
    }

    /** 이 클래스 전용 원본 토픽(운영 member.events.v1처럼 partition 3개). DLT는 알림 설정이 만든다 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic notificationMemberEventDeadLetterTestTopic() {
            return TopicBuilder.name(TOPIC).partitions(PARTITIONS).replicas(1).build();
        }
    }
}
