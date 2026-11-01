package com.hapbang.chat.in.event;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;

import com.hapbang.chat.app.ChatUserFacade;
import com.hapbang.chat.fixture.ChatNativeSql;
import com.hapbang.chat.in.event.kafka.ChatKafkaConfig;
import com.hapbang.chat.in.event.kafka.ChatKafkaConstants;
import com.hapbang.chat.in.event.kafka.ChatKafkaConsumerSettings;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

import jakarta.persistence.EntityManager;

/**
 * 회원 이벤트 소비의 장애 경계를 실제 Kafka로 검증한다(설계 문서 5.3 "재시도·DLT", 계약 control-recording.md
 * "Kafka offset은 이 커밋 뒤에 처리한다").
 * <ul>
 *     <li>DLT 발행 실패를 처리 성공으로 보지 않는다: offset을 커밋하지 않고 같은 레코드를 다시 처리한다.</li>
 *     <li>DB 커밋 뒤 offset 커밋 전 종료(재시작 뒤 같은 레코드 재수신)와 커밋 뒤 리스너 실패(재시도)가 멱등이다.</li>
 *     <li>읽을 수 없는 값·종류를 모르는 값은 DLT로 보내고 파티션의 다음 레코드를 막지 않는다.</li>
 * </ul>
 * 이 클래스만 쓰는 토픽·DLT·소비자 그룹을 UUID로 만들고, 파티션 하나에 회원 ID를 테스트마다 새로 쓴다.
 * DLT 발행 실패는 DLT 발행 템플릿 빈을 감싸 재현한다.
 */
@IntegrationTest
// 이 클래스만 쓰는 토픽·그룹 설정의 컨텍스트라 끝나면 닫아 Kafka 소비자를 남기지 않는다
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, ChatUserEventRecoveryTest.Topics.class})
class ChatUserEventRecoveryTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String TOPIC = "member.events.v1.recovery-" + SUFFIX;
    private static final String GROUP = "chat-app-recovery-" + SUFFIX;
    private static final String DLT = TOPIC + "." + GROUP + ".DLT";
    private static final String LISTENER_ID = "chat-member-events";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final AtomicLong NEXT_ID = new AtomicLong(200_000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(ChatKafkaConstants.MEMBER_EVENTS_ENABLED, () -> "true");
    }

    /** 이 클래스 전용 토픽·그룹·DLT. 재시도는 1회·첫 간격 100ms, DLT 발행 대기는 2초로 줄인다(운영은 3회·1초·10초) */
    @TestBean(name = ChatKafkaConfig.MEMBER_EVENT_SETTINGS)
    private ChatKafkaConsumerSettings memberEventSettings;

    static ChatKafkaConsumerSettings memberEventSettings() {
        return new ChatKafkaConsumerSettings(TOPIC, GROUP, DLT, 1, Duration.ofMillis(100),
                ChatKafkaConstants.RETRY_MAX_INTERVAL, Duration.ofSeconds(2), ChatKafkaConstants.DEAD_LETTER_PARTITIONS,
                ChatKafkaConstants.DEAD_LETTER_REPLICAS);
    }

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @MockitoSpyBean(name = "chatMemberEventDeadLetterTemplate")
    private KafkaTemplate<String, Object> deadLetterTemplate;

    @MockitoSpyBean
    private ChatUserFacade chatUserFacade;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private ChatUserRepository chatUserRepository;

    private ChatNativeSql sql;

    private KafkaProducer<String, byte[]> producer;
    // DLT 발행을 실패시킬지와 회원 ID(key)별 DLT 발행 시도 수
    private final AtomicBoolean deadLetterDown = new AtomicBoolean();
    private final Map<String, AtomicInteger> deadLetterAttempts = new ConcurrentHashMap<>();

    @Autowired
    void setUpSql(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        sql = ChatNativeSql.of(entityManager, transactionManager);
    }

    @BeforeEach
    void setUp() {
        clearControlTables();
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
        // 스파이는 테스트마다 초기화되므로 매번 다시 감싼다
        doAnswer(invocation -> {
            ProducerRecord<?, ?> record = invocation.getArgument(0);
            deadLetterAttempts.computeIfAbsent(String.valueOf(record.key()), key -> new AtomicInteger())
                    .incrementAndGet();
            if (deadLetterDown.get()) {
                return CompletableFuture.failedFuture(new KafkaException("DLT 발행 실패(테스트)"));
            }
            return invocation.callRealMethod();
        }).when(deadLetterTemplate).send(any(ProducerRecord.class));
    }

    @AfterEach
    void tearDown() {
        deadLetterDown.set(false);
        MessageListenerContainer container = listenerRegistry.getListenerContainer(LISTENER_ID);
        if (container != null && !container.isRunning()) {
            container.start();
        }
        producer.close(Duration.ofSeconds(5));
        clearControlTables();
    }

    @Test
    void DLT_발행이_실패하면_offset을_커밋하지_않고_같은_레코드를_다시_처리하며_뒤_레코드로_넘어가지_않는다() throws Exception {
        long id = nextId();
        long next = nextId();
        deadLetterDown.set(true);

        // 가입보다 먼저 온 변경: 재시도 뒤 DLT로 보내야 하는 레코드
        RecordMetadata failing = modified(id, "게스트", Role.INFLUENCER, UserStatus.ACTIVE, 1L);
        joined(next, "다음회원", Role.INFLUENCER, UserStatus.ACTIVE, 0L);

        // DLT 발행이 실패할 때마다 같은 레코드를 처음부터(재시도 포함) 다시 처리한다
        await().atMost(TIMEOUT).until(() -> deadLetterAttemptsOf(id) >= 3);
        verify(chatUserFacade, atLeast(4)).syncModified(argThat(user -> user.id() == id));
        assertThat(committedOffset()).as("실패한 레코드를 넘는 offset을 커밋하지 않는다")
                .satisfiesAnyOf(offset -> assertThat(offset).isNull(),
                        offset -> assertThat(offset).isLessThanOrEqualTo(failing.offset()));
        assertThat(chatUserRepository.findById(next)).as("뒤 레코드를 건너뛰어 처리하지 않는다").isEmpty();
        assertThat(deadLetterKeys()).doesNotContain(String.valueOf(id));

        // DLT가 복구되면 DLT로 넘기고 offset이 넘어가 다음 레코드를 처리한다
        deadLetterDown.set(false);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(next)).isPresent());
        assertThat(deadLetterKeys()).contains(String.valueOf(id));
        assertThat(chatUserRepository.findById(id)).isEmpty();
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(committedOffset()).isGreaterThan(failing.offset()));
    }

    @Test
    void DB_커밋_뒤_offset_커밋_전에_멈춰_같은_레코드를_다시_받아도_회원과_제어_작업이_한_번만_남는다() throws Exception {
        long id = nextId();
        RecordMetadata first = joined(id, "호스트", Role.INFLUENCER, UserStatus.ACTIVE, 1L);
        joined(id, "호스트", Role.INFLUENCER, UserStatus.ACTIVE, 1L);
        modified(id, "호스트", Role.USER, UserStatus.ACTIVE, 2L);
        awaitProcessed();
        Map<String, Object> before = chatUserRow(id);
        List<Map<String, Object>> controlBefore = controlRows(id);
        assertThat(controlBefore).hasSize(1);

        // 프로세스 종료 뒤 재시작: DB는 커밋됐지만 offset은 첫 레코드 앞에 남아 있던 상황을 만든다
        MessageListenerContainer container = listenerRegistry.getListenerContainer(LISTENER_ID);
        container.stop();
        await().atMost(TIMEOUT).until(() -> !container.isRunning());
        rewindTo(first.offset());
        container.start();
        awaitProcessed();

        // 세 레코드를 실제로 다시 받았다(가입 2번 + 변경 1번이 한 번 더)
        verify(chatUserFacade, times(4)).syncJoined(argThat(user -> user.id() == id));
        verify(chatUserFacade, times(2)).syncModified(argThat(user -> user.id() == id));
        assertThat(sql.single("select count(*) from chats.chat_user where id = :id", Long.class, Map.of("id", id)))
                .isEqualTo(1L);
        // 다시 받은 레코드는 아무것도 고치지 않는다(낙관적 락 버전도 그대로)
        assertThat(chatUserRow(id)).isEqualTo(before);
        assertThat(controlRows(id)).isEqualTo(controlBefore);
        assertThat(sql.single("select count(*) from chats.chat_publish_checkpoint where job_id = :jobId", Long.class,
                Map.of("jobId", controlBefore.getFirst().get("control_id")))).isEqualTo(1L);
        assertThat(deadLetterKeys()).doesNotContain(String.valueOf(id));
    }

    @Test
    void DB_커밋_뒤_리스너가_실패해_재시도해도_제어_작업은_하나이고_DLT로_보내지_않는다() {
        long id = nextId();
        joined(id, "호스트", Role.INFLUENCER, UserStatus.ACTIVE, 1L);
        awaitProcessed();
        Set<Long> failedOnce = ConcurrentHashMap.newKeySet();
        // 회원 반영 트랜잭션이 커밋된 뒤, offset 커밋 전에 실패한다
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            UserDto user = invocation.getArgument(0);
            if (user.id() == id && failedOnce.add(id)) {
                throw new IllegalStateException("DB 커밋 뒤 실패(테스트)");
            }
            return result;
        }).when(chatUserFacade).syncModified(any());

        modified(id, "호스트", Role.USER, UserStatus.ACTIVE, 2L);
        awaitProcessed();

        verify(chatUserFacade, times(2)).syncModified(argThat(user -> user.id() == id));
        assertThat(chatUserRepository.findById(id)).hasValueSatisfying(chatUser -> {
            assertThat(chatUser.getRole()).isEqualTo(Role.USER);
            assertThat(chatUser.getMemberVersion()).isEqualTo(2L);
        });
        assertThat(controlRows(id)).singleElement().satisfies(row -> {
            assertThat(row.get("member_version")).isEqualTo(2L);
            assertThat(row.get("reason")).isEqualTo("MEMBER_MODIFIED");
        });
        assertThat(sql.single("select count(*) from chats.chat_publish_checkpoint", Long.class))
                .isEqualTo(1L);
        assertThat(deadLetterKeys()).doesNotContain(String.valueOf(id));
    }

    @Test
    void 읽을_수_없는_값과_타입_헤더가_없는_값은_리스너를_부르지_않고_DLT로_보내며_뒤_레코드를_막지_않는다() {
        long malformed = nextId();
        long noType = nextId();
        long next = nextId();

        send(UserModifiedEvent.class.getName(), malformed, "{not-json".getBytes(StandardCharsets.UTF_8));
        send(null, noType, userJson(noType, "타입없음", Role.ADMIN, UserStatus.ACTIVE, 1L)
                .getBytes(StandardCharsets.UTF_8));
        joined(next, "다음회원", Role.INFLUENCER, UserStatus.ACTIVE, 0L);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(next)).isPresent());
        Map<String, ConsumerRecord<String, byte[]>> deadLetters = deadLetters();
        assertThat(deadLetters).containsKeys(String.valueOf(malformed), String.valueOf(noType));
        assertThat(exceptionClasses(deadLetters.get(String.valueOf(malformed)))).contains("DeserializationException");
        assertThat(exceptionClasses(deadLetters.get(String.valueOf(noType)))).contains("DeserializationException");
        // DLT에는 받은 원본 바이트를 그대로 남긴다
        assertThat(new String(deadLetters.get(String.valueOf(malformed)).value(), StandardCharsets.UTF_8))
                .isEqualTo("{not-json");
        // 역직렬화 실패는 재시도하지 않고 한 번에 DLT로 보낸다
        assertThat(deadLetterAttemptsOf(malformed)).isEqualTo(1);
        assertThat(deadLetterAttemptsOf(noType)).isEqualTo(1);
        verify(chatUserFacade, never()).syncModified(argThat(user -> user.id() == malformed || user.id() == noType));
        verify(chatUserFacade, never()).syncJoined(argThat(user -> user.id() == malformed || user.id() == noType));
        assertThat(chatUserRepository.findById(noType)).isEmpty();
    }

    @Test
    void 신뢰_패키지에_있지만_처리기가_없는_종류는_건너뛰고_DLT로_보내지_않으며_뒤_레코드를_처리한다() throws Exception {
        long unknown = nextId();
        long next = nextId();

        // 같은 이벤트 패키지의 다른 레코드(로그아웃)는 chat이 처리하지 않는 종류다. 회원 쪽이 같은 토픽에 더할 수 있다
        RecordMetadata skipped = send("com.hapbang.shared.member.event.UserLoggedOutEvent", unknown,
                ("{\"userId\":" + unknown + ",\"sessionId\":\"s\"}").getBytes(StandardCharsets.UTF_8));
        joined(next, "다음회원", Role.INFLUENCER, UserStatus.ACTIVE, 0L);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(next)).isPresent());
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(committedOffset()).isGreaterThan(skipped.offset()));
        assertThat(deadLetterAttemptsOf(unknown)).isZero();
        assertThat(deadLetterKeys()).doesNotContain(String.valueOf(unknown));
        assertThat(chatUserRepository.findById(unknown)).isEmpty();
    }

    @Test
    void 값이_없는_레코드는_건너뛰고_DLT로_보내지_않으며_뒤_레코드를_처리한다() {
        long tombstone = nextId();
        long next = nextId();

        send(UserModifiedEvent.class.getName(), tombstone, null);
        joined(next, "다음회원", Role.INFLUENCER, UserStatus.ACTIVE, 0L);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(next)).isPresent());
        assertThat(deadLetterAttemptsOf(tombstone)).isZero();
        assertThat(deadLetterKeys()).doesNotContain(String.valueOf(tombstone));
        verify(chatUserFacade, never()).syncModified(argThat(user -> user.id() == tombstone));
        assertThat(chatUserRepository.findById(tombstone)).isEmpty();
    }

    /**
     * 역직렬화기의 기본 신뢰 타입({@code java.lang}·{@code java.util})은 읽힐 수 있지만 회원 이벤트 신뢰 패키지 밖이라 건너뛰지 않고
     * 재시도 없이 한 번에 DLT로 보낸다. 값은 로그에 남기지 않는다. 내용 타입 헤더가 없는 컬렉션({@code java.util.HashMap})과
     * 상위 패키지의 없는 클래스는 역직렬화 실패로 DLT다.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 기본_신뢰_타입과_상위_패키지_타입은_신뢰_패키지_밖이라_DLT로_보내고_값을_로그에_남기지_않는다(CapturedOutput output) {
        long string = nextId();
        long uuid = nextId();
        long map = nextId();
        long parent = nextId();
        long next = nextId();
        String secret = UUID.randomUUID().toString();
        String secretUuid = UUID.randomUUID().toString();

        send("java.lang.String", string, ("\"" + secret + "\"").getBytes(StandardCharsets.UTF_8));
        send("java.util.UUID", uuid, ("\"" + secretUuid + "\"").getBytes(StandardCharsets.UTF_8));
        send("java.util.HashMap", map, ("{\"a\":\"" + secret + "\"}").getBytes(StandardCharsets.UTF_8));
        send("com.hapbang.shared.member.MemberLoggedOut", parent,
                ("{\"userId\":" + parent + "}").getBytes(StandardCharsets.UTF_8));
        joined(next, "다음회원", Role.INFLUENCER, UserStatus.ACTIVE, 0L);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(next)).isPresent());
        await().atMost(TIMEOUT).until(() -> deadLetterKeys().containsAll(List.of(String.valueOf(string),
                String.valueOf(uuid), String.valueOf(map), String.valueOf(parent))));
        Map<String, ConsumerRecord<String, byte[]>> deadLetters = deadLetters();
        for (long id : List.of(string, uuid)) {
            assertThat(exceptionClasses(deadLetters.get(String.valueOf(id)))).as("회원 %d", id)
                    .contains("RejectedEventException");
        }
        for (long id : List.of(map, parent)) {
            assertThat(exceptionClasses(deadLetters.get(String.valueOf(id)))).as("회원 %d", id)
                    .contains("DeserializationException");
        }
        for (long id : List.of(string, uuid, map, parent)) {
            assertThat(deadLetterAttemptsOf(id)).as("회원 %d", id).isEqualTo(1);
        }
        assertThat(output.getAll()).doesNotContain(secret).doesNotContain(secretUuid);
    }

    /**
     * 값 바이트가 있는데 읽은 결과가 null인 레코드({@code java.lang.Void} + 본문, {@code java.lang.String} + JSON {@code null})는
     * tombstone이 아니라 역직렬화 실패로 DLT에 보낸다. 값 바이트가 없는 진짜 tombstone은 그대로 건너뛴다.
     */
    @Test
    void 읽은_결과가_null인_값은_tombstone과_구분해_DLT로_보내고_진짜_tombstone은_건너뛴다() {
        long voidType = nextId();
        long jsonNull = nextId();
        long tombstone = nextId();
        long next = nextId();

        send("java.lang.Void", voidType, "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        send("java.lang.String", jsonNull, "null".getBytes(StandardCharsets.UTF_8));
        send(UserJoinedEvent.class.getName(), tombstone, null);
        joined(next, "다음회원", Role.INFLUENCER, UserStatus.ACTIVE, 0L);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(next)).isPresent());
        await().atMost(TIMEOUT).until(() -> deadLetterKeys().containsAll(List.of(String.valueOf(voidType),
                String.valueOf(jsonNull))));
        Map<String, ConsumerRecord<String, byte[]>> deadLetters = deadLetters();
        for (long id : List.of(voidType, jsonNull)) {
            assertThat(exceptionClasses(deadLetters.get(String.valueOf(id)))).as("회원 %d", id)
                    .contains("DeserializationException");
            assertThat(deadLetterAttemptsOf(id)).as("회원 %d", id).isEqualTo(1);
        }
        assertThat(new String(deadLetters.get(String.valueOf(jsonNull)).value(), StandardCharsets.UTF_8))
                .isEqualTo("null");
        assertThat(deadLetterAttemptsOf(tombstone)).isZero();
        assertThat(deadLetters).doesNotContainKey(String.valueOf(tombstone));
    }

    /**
     * 회원 앱이 신뢰 패키지 안에 새 이벤트 클래스를 더해 먼저 배포하면 chat-app 클래스패스에는 그 클래스가 없다.
     * 오류로 보지 않고 건너뛴다(사용자 결정, 2026-10-30). 신뢰 패키지의 하위 패키지는 신뢰 범위가 아니라 그대로 DLT로 간다.
     */
    @Test
    void 신뢰_패키지_안이지만_클래스패스에_없는_새_종류는_건너뛰고_하위_패키지는_DLT로_보낸다() throws Exception {
        long missing = nextId();
        long subPackage = nextId();
        long next = nextId();

        RecordMetadata skipped = send("com.hapbang.shared.member.event.MemberLoggedOut", missing,
                ("{\"userId\":" + missing + "}").getBytes(StandardCharsets.UTF_8));
        send("com.hapbang.shared.member.event.sub.MemberLoggedOut", subPackage,
                ("{\"userId\":" + subPackage + "}").getBytes(StandardCharsets.UTF_8));
        joined(next, "다음회원", Role.INFLUENCER, UserStatus.ACTIVE, 0L);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(next)).isPresent());
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(committedOffset()).isGreaterThan(skipped.offset()));
        assertThat(deadLetterAttemptsOf(missing)).isZero();
        Map<String, ConsumerRecord<String, byte[]>> deadLetters = deadLetters();
        assertThat(deadLetters).doesNotContainKey(String.valueOf(missing));
        assertThat(exceptionClasses(deadLetters.get(String.valueOf(subPackage)))).contains("DeserializationException");
        assertThat(deadLetterAttemptsOf(subPackage)).isEqualTo(1);
    }

    @Test
    void 본문을_읽지_못한_레코드는_원본_타입_헤더와_바이트로_DLT에_남고_본문을_고쳐_다시_넣으면_처리된다() {
        long id = nextId();
        // 타입 헤더는 올바르지만 본문의 역할 값이 계약에 없어 읽지 못한다
        byte[] broken = ("{\"user\":{\"id\":" + id + ",\"nickname\":\"가입\",\"role\":\"NOT_A_ROLE\","
                + "\"status\":\"ACTIVE\",\"version\":0}}").getBytes(StandardCharsets.UTF_8);

        send(UserJoinedEvent.class.getName(), id, broken);

        await().atMost(TIMEOUT).until(() -> deadLetterKeys().contains(String.valueOf(id)));
        ConsumerRecord<String, byte[]> deadLetter = deadLetters().get(String.valueOf(id));
        assertThat(exceptionClasses(deadLetter)).contains("DeserializationException");
        assertThat(deadLetter.value()).isEqualTo(broken);
        assertThat(typeIds(deadLetter)).containsExactly(UserJoinedEvent.class.getName());
        assertThat(chatUserRepository.findById(id)).isEmpty();

        // 운영자가 DLT 레코드의 key·타입 헤더는 그대로 두고 본문만 고쳐 원래 토픽에 다시 넣는다
        send(typeIds(deadLetter).getFirst(), Long.parseLong(deadLetter.key()),
                userJson(id, "가입", Role.INFLUENCER, UserStatus.ACTIVE, 0L).getBytes(StandardCharsets.UTF_8));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(id))
                .hasValueSatisfying(chatUser -> assertThat(chatUser.getRole()).isEqualTo(Role.INFLUENCER)));
    }

    @Test
    void 리스너_실패로_DLT에_보낸_레코드는_올바른_타입_헤더를_하나만_가진다() {
        long id = nextId();

        // 가입보다 먼저 온 변경: 읽기는 성공하고 리스너가 실패해 재시도 뒤 DLT로 간다
        modified(id, "게스트", Role.INFLUENCER, UserStatus.ACTIVE, 1L);

        await().atMost(TIMEOUT).until(() -> deadLetterKeys().contains(String.valueOf(id)));
        ConsumerRecord<String, byte[]> deadLetter = deadLetters().get(String.valueOf(id));
        assertThat(exceptionClasses(deadLetter)).contains("ChatException");
        assertThat(typeIds(deadLetter)).containsExactly(UserModifiedEvent.class.getName());
        assertThat(new String(deadLetter.value(), StandardCharsets.UTF_8)).contains("\"id\":" + id);
    }

    @Test
    void 다시_처리해도_같은_실패는_재시도_없이_한_번에_DLT로_보내고_가입_전_변경만_재시도한다() {
        long invalidJoin = nextId();
        long invalidModify = nextId();
        long beforeJoin = nextId();

        // 복제할 수 없는 회원 정보(닉네임 공백) → INVALID_REQUEST
        joined(invalidJoin, " ", Role.INFLUENCER, UserStatus.ACTIVE, 0L);
        modified(invalidModify, " ", Role.INFLUENCER, UserStatus.ACTIVE, 1L);
        // 가입 전에 온 변경: 가입이 늦게 올 수 있어 재시도한다
        modified(beforeJoin, "게스트", Role.INFLUENCER, UserStatus.ACTIVE, 1L);

        await().atMost(TIMEOUT).until(() -> deadLetterKeys().containsAll(List.of(String.valueOf(invalidJoin),
                String.valueOf(invalidModify), String.valueOf(beforeJoin))));
        Map<String, ConsumerRecord<String, byte[]>> deadLetters = deadLetters();
        verify(chatUserFacade, times(1)).syncJoined(argThat(user -> user.id() == invalidJoin));
        verify(chatUserFacade, times(1)).syncModified(argThat(user -> user.id() == invalidModify));
        for (long id : List.of(invalidJoin, invalidModify)) {
            assertThat(exceptionClasses(deadLetters.get(String.valueOf(id))))
                    .as("회원 %d", id).contains("RejectedEventException");
            assertThat(deadLetterAttemptsOf(id)).as("회원 %d", id).isEqualTo(1);
        }
        // 재시도 1번(테스트 설정) = 처음 처리 포함 2번 시도한 뒤 DLT
        verify(chatUserFacade, times(2)).syncModified(argThat(user -> user.id() == beforeJoin));
        assertThat(exceptionClasses(deadLetters.get(String.valueOf(beforeJoin)))).contains("ChatException")
                .doesNotContain("RejectedEventException");
        assertThat(chatUserRepository.findById(invalidJoin)).isEmpty();
    }

    @Test
    void 테스트세션_값이_없는_레코드는_기본_처리기에서_건너뛰어_DLT로_보내지_않는다() {
        long tombstone = nextId();
        long next = nextId();

        send(UserJoinedEvent.class.getName(), tombstone, null);
        joined(next, "다음회원", Role.INFLUENCER, UserStatus.ACTIVE, 0L);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(next)).isPresent());
        // 값이 없는 레코드는 역직렬화 실패가 아니라 KafkaNull로 기본 처리기에 가서 건너뛴다
        assertThat(deadLetterAttemptsOf(tombstone)).isZero();
        assertThat(deadLetterKeys()).doesNotContain(String.valueOf(tombstone));
        verify(chatUserFacade, never()).syncJoined(argThat(user -> user.id() == tombstone));
    }

    @Test
    void 테스트세션_일시적인_DB_오류는_재시도해_반영하고_DLT로_보내지_않는다() {
        long id = nextId();
        Set<Long> failedOnce = ConcurrentHashMap.newKeySet();
        doAnswer(invocation -> {
            UserDto user = invocation.getArgument(0);
            if (user.id() == id && failedOnce.add(id)) {
                throw new TransientDataAccessResourceException("DB 연결 실패(테스트)");
            }
            return invocation.callRealMethod();
        }).when(chatUserFacade).syncJoined(any());

        joined(id, "호스트", Role.INFLUENCER, UserStatus.ACTIVE, 0L);
        awaitProcessed();

        verify(chatUserFacade, times(2)).syncJoined(argThat(user -> user.id() == id));
        assertThat(chatUserRepository.findById(id)).isPresent();
        assertThat(deadLetterAttemptsOf(id)).isZero();
    }

    @Test
    void 테스트세션_계속되는_DB_오류는_설정한_횟수만큼_재시도한_뒤_원래_예외로_DLT에_간다() {
        long id = nextId();
        doAnswer(invocation -> {
            UserDto user = invocation.getArgument(0);
            if (user.id() == id) {
                throw new TransientDataAccessResourceException("DB 연결 실패(테스트)");
            }
            return invocation.callRealMethod();
        }).when(chatUserFacade).syncJoined(any());

        joined(id, "호스트", Role.INFLUENCER, UserStatus.ACTIVE, 0L);
        awaitProcessed();

        // 재시도 1번(테스트 설정) = 처음 처리 포함 2번 시도한 뒤 DLT
        verify(chatUserFacade, times(2)).syncJoined(argThat(user -> user.id() == id));
        assertThat(deadLetterAttemptsOf(id)).isEqualTo(1);
        assertThat(exceptionClasses(deadLetters().get(String.valueOf(id))))
                .contains("TransientDataAccessResourceException").doesNotContain("RejectedEventException");
        assertThat(chatUserRepository.findById(id)).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------

    private RecordMetadata joined(long id, String nickname, Role role, UserStatus status, Long version) {
        return send(UserJoinedEvent.class.getName(), id,
                userJson(id, nickname, role, status, version).getBytes(StandardCharsets.UTF_8));
    }

    private RecordMetadata modified(long id, String nickname, Role role, UserStatus status, Long version) {
        return send(UserModifiedEvent.class.getName(), id,
                userJson(id, nickname, role, status, version).getBytes(StandardCharsets.UTF_8));
    }

    /** member-app의 발행 형식: key = 회원 ID 문자열, value = JSON 바이트, 헤더 {@code __TypeId__} 하나 */
    private RecordMetadata send(String typeId, long key, byte[] value) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(TOPIC, String.valueOf(key), value);
        if (typeId != null) {
            record.headers().add("__TypeId__", typeId.getBytes(StandardCharsets.UTF_8));
        }
        try {
            return producer.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("회원 이벤트를 보내지 못했습니다.", e);
        }
    }

    private static String userJson(long id, String nickname, Role role, UserStatus status, Long version) {
        return "{\"user\":{\"id\":" + id + ",\"nickname\":\"" + nickname + "\",\"role\":\"" + role.name()
                + "\",\"status\":\"" + status.name() + "\",\"version\":" + version + "}}";
    }

    /** 파티션이 하나라, 마지막에 보낸 표식 회원의 가입이 반영되면 앞서 보낸 레코드의 처리도 끝났다 */
    private void awaitProcessed() {
        long marker = nextId();
        joined(marker, "표식", Role.USER, UserStatus.ACTIVE, 0L);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(marker)).isPresent());
    }

    private int deadLetterAttemptsOf(long id) {
        AtomicInteger count = deadLetterAttempts.get(String.valueOf(id));
        return count == null ? 0 : count.get();
    }

    private Admin admin() {
        Properties config = new Properties();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return Admin.create(config);
    }

    /** 소비자 그룹이 커밋한 다음 offset. 커밋한 적이 없으면 null */
    private Long committedOffset() throws Exception {
        try (Admin admin = admin()) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS).get(PARTITION);
            return committed == null ? null : committed.offset();
        }
    }

    /** 멈춘 소비자 그룹의 offset을 되돌린다. 그룹에서 소비자가 빠질 때까지 다시 시도한다 */
    private void rewindTo(long offset) {
        try (Admin admin = admin()) {
            await().atMost(TIMEOUT).ignoreExceptions().untilAsserted(() -> admin
                    .alterConsumerGroupOffsets(GROUP, Map.of(PARTITION, new OffsetAndMetadata(offset)))
                    .all().get(10, TimeUnit.SECONDS));
        }
    }

    /** DLT를 처음부터 끝 offset까지 읽는다(회원 ID key → 마지막 레코드) */
    private Map<String, ConsumerRecord<String, byte[]>> deadLetters() {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        Map<String, ConsumerRecord<String, byte[]>> found = new ConcurrentHashMap<>();
        TopicPartition dlt = new TopicPartition(DLT, 0);
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
                new ByteArrayDeserializer())) {
            consumer.assign(List.of(dlt));
            consumer.seekToBeginning(List.of(dlt));
            long end = consumer.endOffsets(List.of(dlt)).get(dlt);
            await().atMost(TIMEOUT).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> found.put(record.key(), record));
                return consumer.position(dlt) >= end;
            });
        }
        return found;
    }

    private Set<String> deadLetterKeys() {
        return deadLetters().keySet();
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** 레코드의 {@code __TypeId__} 헤더 값 전부(중복 여부까지 본다) */
    private static List<String> typeIds(ConsumerRecord<?, ?> record) {
        List<String> values = new ArrayList<>();
        record.headers().headers("__TypeId__")
                .forEach(header -> values.add(new String(header.value(), StandardCharsets.UTF_8)));
        return values;
    }

    private static String exceptionClasses(ConsumerRecord<?, ?> record) {
        return header(record, KafkaHeaders.DLT_EXCEPTION_FQCN) + " "
                + header(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
    }

    private Map<String, Object> chatUserRow(long id) {
        return sql.row("select nickname, role, status, member_version, version from chats.chat_user where id = :id",
                Map.of("id", id));
    }

    private List<Map<String, Object>> controlRows(long userId) {
        return new ArrayList<>(sql.rows("""
                select control_id, control_type, member_version, reason, status
                from chats.chat_control_outbox where user_id = :userId order by id""", Map.of("userId", userId)));
    }

    private void clearControlTables() {
        sql.update("delete from chats.chat_publish_checkpoint");
        sql.update("delete from chats.chat_control_outbox");
    }

    private static long nextId() {
        return NEXT_ID.incrementAndGet();
    }

    /** 이 클래스 전용 토픽과 DLT. 리스너 컨테이너가 시작하기 전에 KafkaAdmin이 만든다 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic chatUserEventRecoveryTopic() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }

        @Bean
        NewTopic chatUserEventRecoveryDeadLetterTopic() {
            return TopicBuilder.name(DLT).partitions(1).replicas(1).build();
        }
    }
}
