package com.hapbang.chat.in.event;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.hapbang.chat.app.ChatConnectionControl;
import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.chat.out.JdbcChatConnectionControl;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

/**
 * 회원 이벤트를 실제 Kafka(Testcontainers)로 받아 채팅 회원으로 복제하는지 검증한다(설계 문서 5.3).
 * <p>
 * member-app의 발행 형식 그대로 보낸다: key는 회원 ID 문자열, value는 shared 레코드의 JSON 바이트, 헤더는 {@code __TypeId__}(레코드 클래스
 * 이름) 하나. 이 클래스만 쓰는 토픽·DLT·소비자 그룹을 UUID로 만들고, 테스트마다 새 회원 ID를 쓴다. 파티션이 하나라 마지막에 보낸
 * 표식 회원이 반영되면 앞서 보낸 레코드도 처리(또는 DLT)가 끝난 것이다. 수신은 모두 제한 시간 안에서 기다린다.
 * <p>
 * 제어 outbox·발행 체크포인트는 JPA 엔티티가 아니라 공통 DB 정리에 포함되지 않으므로 테스트마다 직접 지운다.
 */
@IntegrationTest
// 이 클래스만 쓰는 토픽·그룹 설정의 컨텍스트라 끝나면 닫아 Kafka 소비자를 남기지 않는다
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, ChatUserEventListenerTest.Topics.class,
        ChatUserEventListenerTest.FailableConnectionControl.class})
class ChatUserEventListenerTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String TOPIC = "member.events.v1.test-" + SUFFIX;
    private static final String GROUP = "chat-app-test-" + SUFFIX;
    private static final String DLT = TOPIC + "." + GROUP + ".DLT";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final AtomicLong NEXT_ID = new AtomicLong(100_000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("hapbang.chat.member-events.enabled", () -> "true");
        registry.add("hapbang.chat.member-events.topic", () -> TOPIC);
        registry.add("hapbang.chat.member-events.group-id", () -> GROUP);
        registry.add("hapbang.chat.member-events.max-retries", () -> "2");
        // 시험용 원본 토픽은 partition 1개라 DLT도 1개로 맞춘다(운영 기본값은 원본과 같은 3)
        registry.add("hapbang.chat.member-events.dead-letter-partitions", () -> "1");
        registry.add("hapbang.chat.member-events.retry-initial-interval", () -> "100ms");
    }

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Autowired
    private ChatUserRepository chatUserRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private ChatMemberEventTopicVerifier topicVerifier;

    @Autowired
    private FailableConnectionControl connectionControl;

    private KafkaProducer<String, byte[]> producer;

    @BeforeEach
    void setUp() {
        clearControlTables();
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
    }

    @AfterEach
    void tearDown() {
        producer.close(Duration.ofSeconds(5));
        connectionControl.reset();
        clearControlTables();
    }

    @Test
    void 가입_이벤트를_받으면_채팅_회원으로_복제하고_제어_작업은_남기지_않는다() {
        long id = nextId();

        joined(id, "호스트", Role.INFLUENCER, UserStatus.ACTIVE, 0L);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(id))
                .hasValueSatisfying(chatUser -> {
                    assertThat(chatUser.getNickname()).isEqualTo("호스트");
                    assertThat(chatUser.getRole()).isEqualTo(Role.INFLUENCER);
                    assertThat(chatUser.getMemberVersion()).isEqualTo(0L);
                    assertThat(chatUser.canChat()).isTrue();
                }));
        assertThat(controlRows(id)).isEmpty();
    }

    @Test
    void 채팅할_수_없는_회원이_가입해도_만들기만_하고_제어_작업은_남기지_않는다() {
        long id = nextId();

        joined(id, "일반유저", Role.USER, UserStatus.ACTIVE, 0L);
        awaitProcessed();

        assertThat(chatUserRepository.findById(id)).get().extracting(ChatUser::canChat).isEqualTo(false);
        assertThat(controlRows(id)).isEmpty();
    }

    @Test
    void 같은_가입_이벤트를_두_번_받아도_한_명만_복제한다() {
        long id = nextId();

        joined(id, "게스트", Role.INFLUENCER, UserStatus.ACTIVE, 0L);
        joined(id, "게스트", Role.INFLUENCER, UserStatus.ACTIVE, 0L);
        awaitProcessed();

        assertThat(jdbcTemplate.queryForObject("select count(*) from chats.chat_user where id = ?", Long.class, id))
                .isEqualTo(1L);
    }

    @Test
    void 이미_반영한_버전보다_옛_변경_이벤트는_반영하지_않고_제어_작업도_남기지_않는다() {
        long id = nextId();
        joined(id, "가입닉네임", Role.INFLUENCER, UserStatus.ACTIVE, 1L);
        modified(id, "최신닉네임", Role.INFLUENCER, UserStatus.ACTIVE, 3L);

        modified(id, "옛닉네임", Role.INFLUENCER, UserStatus.SUSPENDED, 2L);
        awaitProcessed();

        assertThat(chatUserRepository.findById(id)).hasValueSatisfying(chatUser -> {
            assertThat(chatUser.getNickname()).isEqualTo("최신닉네임");
            assertThat(chatUser.getMemberVersion()).isEqualTo(3L);
            assertThat(chatUser.canChat()).isTrue();
        });
        assertThat(controlRows(id)).isEmpty();
    }

    @Test
    void 가입보다_먼저_온_변경_이벤트는_만들지_않고_재시도_뒤_DLT로_보낸다() {
        long id = nextId();

        modified(id, "게스트", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 1L);

        ConsumerRecord<String, byte[]> deadLetter = awaitDeadLetter(id);
        assertThat(new String(deadLetter.value(), StandardCharsets.UTF_8)).contains("\"id\":" + id);
        assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(TOPIC);
        assertThat(exceptionClasses(deadLetter)).contains("ChatException");
        assertThat(chatUserRepository.findById(id)).isEmpty();
        assertThat(connectionControl.calls(id)).isZero();

        // DLT로 넘긴 뒤 offset이 넘어가 다음 레코드(같은 회원의 가입)를 처리한다
        joined(id, "게스트", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 0L);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(id)).isPresent());
    }

    @Test
    void 강등을_반영하면_같은_트랜잭션에_제어_작업_하나와_대기_체크포인트를_남긴다() {
        long id = nextId();
        joined(id, "호스트", Role.INFLUENCER, UserStatus.ACTIVE, 1L);

        modified(id, "호스트", Role.USER, UserStatus.ACTIVE, 2L);
        // 같은 변경 이벤트가 다시 와도(재전송) 이미 반영한 버전이라 다시 기록하지 않는다
        modified(id, "호스트", Role.USER, UserStatus.ACTIVE, 2L);
        awaitProcessed();

        assertThat(chatUserRepository.findById(id)).get().extracting(ChatUser::canChat).isEqualTo(false);
        List<Map<String, Object>> rows = controlRows(id);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("member_version")).isEqualTo(2L);
            assertThat(row.get("reason")).isEqualTo("MEMBER_MODIFIED");
            assertThat(row.get("control_type")).isEqualTo("RECHECK_USER");
            assertThat(row.get("status")).isEqualTo("PENDING");
        });
        assertThat(checkpointRows(rows.getFirst().get("control_id"))).singleElement().satisfies(row -> {
            assertThat(row.get("consumer_group")).isEqualTo("chat-control-v1");
            assertThat(row.get("status")).isEqualTo("PENDING");
        });
        assertThat(connectionControl.calls(id)).isEqualTo(1);
    }

    @Test
    void 정지를_반영하면_제어_작업을_남긴다() {
        long id = nextId();
        joined(id, "게스트", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 1L);

        modified(id, "게스트", Role.VERIFIED_INFLUENCER, UserStatus.SUSPENDED, 2L);
        awaitProcessed();

        assertThat(controlRows(id)).singleElement()
                .satisfies(row -> assertThat(row.get("member_version")).isEqualTo(2L));
    }

    @Test
    void 이미_채팅할_수_없던_회원이라도_반영한_최신_상태가_채팅_불가면_제어_작업을_남긴다() {
        long id = nextId();
        joined(id, "일반유저", Role.USER, UserStatus.ACTIVE, 1L);

        modified(id, "새닉네임", Role.USER, UserStatus.ACTIVE, 2L);
        awaitProcessed();

        assertThat(controlRows(id)).singleElement()
                .satisfies(row -> assertThat(row.get("member_version")).isEqualTo(2L));
    }

    @Test
    void 채팅할_수_있는_회원의_프로필만_바뀌면_제어_작업을_남기지_않는다() {
        long id = nextId();
        joined(id, "호스트", Role.INFLUENCER, UserStatus.ACTIVE, 1L);

        modified(id, "새닉네임", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 2L);
        awaitProcessed();

        assertThat(chatUserRepository.findById(id)).hasValueSatisfying(chatUser -> {
            assertThat(chatUser.getNickname()).isEqualTo("새닉네임");
            assertThat(chatUser.getMemberVersion()).isEqualTo(2L);
        });
        assertThat(controlRows(id)).isEmpty();
    }

    @Test
    void 반영이_롤백되면_회원_변경도_제어_작업도_남지_않고_DLT로_보낸다() {
        long id = nextId();
        joined(id, "호스트", Role.INFLUENCER, UserStatus.ACTIVE, 1L);
        awaitProcessed();
        // 제어 작업을 실제로 기록한 뒤 같은 트랜잭션 안에서 실패시킨다
        connectionControl.failAfterRecording(id);

        modified(id, "호스트", Role.USER, UserStatus.ACTIVE, 2L);

        awaitDeadLetter(id);
        // 처음 처리와 재시도 2번 모두 기록까지 갔다가 롤백됐다
        assertThat(connectionControl.calls(id)).isEqualTo(3);
        assertThat(chatUserRepository.findById(id)).hasValueSatisfying(chatUser -> {
            assertThat(chatUser.getRole()).isEqualTo(Role.INFLUENCER);
            assertThat(chatUser.getMemberVersion()).isEqualTo(1L);
        });
        assertThat(controlRows(id)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("select count(*) from chats.chat_publish_checkpoint", Long.class))
                .isZero();
    }

    @Test
    void 신뢰_패키지_밖의_타입_헤더는_재시도_없이_DLT로_보내고_만들지_않는다() {
        long id = nextId();

        // 회원 DTO 패키지(com.hapbang.shared.member.dto)는 이벤트 패키지가 아니라 신뢰하지 않는다
        send("com.hapbang.shared.member.dto.UserDto", id,
                "{\"id\":" + id + ",\"nickname\":\"x\",\"role\":\"ADMIN\",\"status\":\"ACTIVE\",\"version\":1}");

        ConsumerRecord<String, byte[]> deadLetter = awaitDeadLetter(id);
        assertThat(exceptionClasses(deadLetter)).contains("DeserializationException");
        assertThat(chatUserRepository.findById(id)).isEmpty();
    }

    @Test
    void 앱이_설정한_파티션_수로_DLT를_만들고_원본과_같은지_확인한다() {
        Map<String, TopicDescription> topics = kafkaAdmin.describeTopics(DLT);

        assertThat(topics.get(DLT).partitions()).hasSize(1);
        assertThat(topicVerifier.verify()).isEqualTo(ChatMemberEventTopicVerifier.Result.OK);
    }

    // ---------------------------------------------------------------------------------------------

    private void joined(long id, String nickname, Role role, UserStatus status, Long version) {
        send(UserJoinedEvent.class.getName(), id, userJson(id, nickname, role, status, version));
    }

    private void modified(long id, String nickname, Role role, UserStatus status, Long version) {
        send(UserModifiedEvent.class.getName(), id, userJson(id, nickname, role, status, version));
    }

    /** member-app의 발행 형식: key = 회원 ID 문자열, value = JSON 바이트, 헤더 {@code __TypeId__} 하나 */
    private void send(String typeId, long key, String json) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(TOPIC, String.valueOf(key),
                json.getBytes(StandardCharsets.UTF_8));
        record.headers().add("__TypeId__", typeId.getBytes(StandardCharsets.UTF_8));
        try {
            producer.send(record).get(10, TimeUnit.SECONDS);
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

    private ConsumerRecord<String, byte[]> awaitDeadLetter(long key) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
                new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(DLT));
            await().atMost(TIMEOUT).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (String.valueOf(key).equals(record.key())) {
                        found.add(record);
                    }
                });
                return !found.isEmpty();
            });
        }
        return found.getFirst();
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** DLT 예외 헤더(예외·원인 클래스 이름). 리스너 예외는 감싸져 오므로 둘을 함께 본다 */
    private static String exceptionClasses(ConsumerRecord<?, ?> record) {
        return header(record, KafkaHeaders.DLT_EXCEPTION_FQCN) + " "
                + header(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
    }

    private List<Map<String, Object>> controlRows(long userId) {
        return jdbcTemplate.queryForList("""
                select control_id, control_type, member_version, reason, status
                from chats.chat_control_outbox where user_id = ?""", userId);
    }

    private List<Map<String, Object>> checkpointRows(Object controlId) {
        return jdbcTemplate.queryForList(
                "select consumer_group, status from chats.chat_publish_checkpoint where job_id = ?", controlId);
    }

    private void clearControlTables() {
        jdbcTemplate.update("delete from chats.chat_publish_checkpoint");
        jdbcTemplate.update("delete from chats.chat_control_outbox");
    }

    private static long nextId() {
        return NEXT_ID.incrementAndGet();
    }

    /** 이 클래스 전용 원본 토픽. DLT는 chat-app 설정({@link ChatMemberEventKafkaConfig})이 만든다 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic chatUserEventTestTopic() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }
    }

    /**
     * 실제 제어 작업 기록({@link JdbcChatConnectionControl})을 감싸 호출 수를 세고, 지정한 회원은 기록한 뒤 실패시킨다.
     * AOP 프록시 빈을 Mockito로 감싸지 않으려고 테스트 전용 기본 빈으로 둔다.
     */
    @Primary
    @TestComponent
    static class FailableConnectionControl implements ChatConnectionControl {

        private final JdbcChatConnectionControl delegate;
        private final Map<Long, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final Set<Long> failing = ConcurrentHashMap.newKeySet();

        FailableConnectionControl(JdbcChatConnectionControl delegate) {
            this.delegate = delegate;
        }

        @Override
        public void requestRecheck(Long userId, Long memberVersion, ChatControlReason reason) {
            calls.computeIfAbsent(userId, key -> new AtomicInteger()).incrementAndGet();
            delegate.requestRecheck(userId, memberVersion, reason);
            if (failing.contains(userId)) {
                throw new IllegalStateException("제어 작업 기록 뒤 실패");
            }
        }

        void failAfterRecording(Long userId) {
            failing.add(userId);
        }

        int calls(Long userId) {
            AtomicInteger count = calls.get(userId);
            return count == null ? 0 : count.get();
        }

        void reset() {
            calls.clear();
            failing.clear();
        }
    }
}
