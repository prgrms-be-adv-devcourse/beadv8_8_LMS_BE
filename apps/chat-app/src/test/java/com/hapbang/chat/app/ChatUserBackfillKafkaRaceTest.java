package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.chat.app.dto.ChatUserBackfillResult;
import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.chat.out.JdbcChatConnectionControl;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;
import com.sun.net.httpserver.HttpServer;

/**
 * 기존 회원 백필(설계 문서 5.4)과 실제 Kafka 회원 이벤트 소비가 함께 돌 때의 경계를 검증한다.
 * <ul>
 *     <li>백필 페이지 경계: 읽어 둔 옛 스냅샷 페이지가 그 사이 Kafka로 반영된 더 새로운 변경을 되돌리지 않는다(memberVersion).</li>
 *     <li>백필이 더 새로운 스냅샷을 반영한 뒤 늦게 온 가입·변경 이벤트가 되돌리지 않고, 제어 작업도 남기지 않는다.</li>
 *     <li>가입보다 먼저 와 DLT로 간 변경을 백필이 최신 버전으로 수렴시킨다(5.3 "초기 적재 중의 실패는 5.4에서 재처리").</li>
 *     <li>백필과 Kafka 가입·변경이 같은 회원을 동시에 만들거나 고치면 유일 제약·낙관적 락 충돌 뒤 다시 처리해 수렴한다.</li>
 *     <li>백필 반영과 제어 작업 기록은 회원마다 한 트랜잭션이다. 기록 뒤 실패하면 둘 다 남지 않고 실패로 집계된다.</li>
 * </ul>
 * 회원 내부 API는 가짜 HTTP 서버(백필 통합 테스트와 같은 방식), 회원 이벤트는 이 클래스 전용 토픽이다.
 * 가짜 서버 주소 때문에 이 클래스만 쓰는 컨텍스트라 끝나면 닫는다.
 */
@IntegrationTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({KafkaTestcontainersConfig.class, ChatUserBackfillKafkaRaceTest.Topics.class,
        ChatUserBackfillKafkaRaceTest.FailableConnectionControl.class})
class ChatUserBackfillKafkaRaceTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String TOPIC = "member.events.v1.backfill-race-" + SUFFIX;
    private static final String GROUP = "chat-app-backfill-race-" + SUFFIX;
    private static final String DLT = TOPIC + "." + GROUP + ".DLT";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_LIMIT = 10;
    private static final AtomicLong NEXT_ID = new AtomicLong(300_000);

    private static final HttpServer SERVER = startServer();
    private static final ConcurrentSkipListMap<Long, UserDto> MEMBERS = new ConcurrentSkipListMap<>();
    // 회원 원본이 afterId 요청에 응답하기 직전에 실행할 일(페이지를 읽은 시점과 저장 사이의 Kafka 반영 재현)
    private static volatile LongConsumer beforeRespond;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("hapbang.chat.member-events.enabled", () -> "true");
        registry.add("hapbang.chat.member-events.topic", () -> TOPIC);
        registry.add("hapbang.chat.member-events.group-id", () -> GROUP);
        registry.add("hapbang.chat.member-events.max-retries", () -> "1");
        registry.add("hapbang.chat.member-events.retry-initial-interval", () -> "100ms");
        registry.add("hapbang.clients.member.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        // 페이지 응답 직전에 Kafka 반영을 기다리므로 응답 제한 시간을 넉넉히 둔다
        registry.add("hapbang.clients.member.read-timeout", () -> "40s");
        registry.add("hapbang.chat.backfill.page-size", () -> "2");
        registry.add("hapbang.chat.backfill.max-limit", () -> String.valueOf(MAX_LIMIT));
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Autowired
    private ChatUserBackfillService backfillService;

    @Autowired
    private ChatUserSyncService chatUserSyncService;

    @MockitoSpyBean
    private ChatUserRepository chatUserRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FailableConnectionControl connectionControl;

    private KafkaProducer<String, byte[]> producer;

    @BeforeEach
    void setUp() {
        MEMBERS.clear();
        beforeRespond = null;
        clearControlTables();
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
    }

    @AfterEach
    void tearDown() {
        beforeRespond = null;
        connectionControl.reset();
        producer.close(Duration.ofSeconds(5));
        clearControlTables();
    }

    @Test
    void 백필_페이지_경계에서_Kafka로_먼저_반영된_더_새로운_변경을_옛_스냅샷_페이지가_되돌리지_않는다() {
        long base = NEXT_ID.getAndAdd(10);
        long m1 = base + 1;
        long m2 = base + 2;
        long m3 = base + 3;
        long m4 = base + 4;
        for (long id = m1; id <= m4; id++) {
            joined(id, "가입", Role.INFLUENCER, UserStatus.ACTIVE, 1L);
            source(id, "스냅샷", Role.USER, UserStatus.ACTIVE, 2L);
        }
        awaitProcessed();
        // 첫 페이지(m1·m2)를 반영한 뒤 두 번째 페이지(m3·m4)를 읽기 직전에, 두 페이지에 걸친 더 새로운 변경이 Kafka로 반영된다.
        // 두 번째 페이지 응답은 그 전에 읽은 옛 스냅샷(v2)이다
        beforeRespond = afterId -> {
            if (afterId == m2) {
                beforeRespond = null;
                modified(m2, "최신", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 3L);
                modified(m3, "최신", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 3L);
                await().atMost(TIMEOUT).until(() -> memberVersion(m2) == 3L && memberVersion(m3) == 3L);
            }
        };

        ChatUserBackfillResult result = backfillService.backfill(base, MAX_LIMIT);

        assertThat(result).extracting(ChatUserBackfillResult::scanned, ChatUserBackfillResult::updated,
                        ChatUserBackfillResult::skippedStale, ChatUserBackfillResult::failed,
                        ChatUserBackfillResult::completed)
                .containsExactly(4L, 3L, 1L, 0L, true);
        // 페이지 1의 m2: 백필(v2) 뒤 Kafka(v3)가 이겼다. 페이지 2의 m3: 옛 페이지(v2)가 Kafka(v3)를 되돌리지 않았다
        for (long id : List.of(m2, m3)) {
            assertThat(chatUserRepository.findById(id)).hasValueSatisfying(chatUser -> {
                assertThat(chatUser.getNickname()).isEqualTo("최신");
                assertThat(chatUser.getMemberVersion()).isEqualTo(3L);
                assertThat(chatUser.canChat()).isTrue();
            });
        }
        for (long id : List.of(m1, m4)) {
            assertThat(chatUserRepository.findById(id)).hasValueSatisfying(chatUser -> {
                assertThat(chatUser.getMemberVersion()).isEqualTo(2L);
                assertThat(chatUser.canChat()).isFalse();
            });
        }
        // 백필이 갱신해 채팅 불가가 된 m1·m2·m4만 백필 사유로 남는다. 건너뛴 m3과 채팅 가능한 Kafka 반영은 남기지 않는다
        assertThat(controlRows()).extracting(row -> row.get("user_id"), row -> row.get("member_version"),
                        row -> row.get("reason"))
                .containsExactly(tuple(m1, 2L, "MEMBER_BACKFILL"), tuple(m2, 2L, "MEMBER_BACKFILL"),
                        tuple(m4, 2L, "MEMBER_BACKFILL"));
    }

    @Test
    void 백필이_더_새로운_스냅샷을_반영한_뒤_늦게_온_가입과_변경_이벤트는_되돌리지_않고_제어_작업도_남기지_않는다() {
        long id = NEXT_ID.incrementAndGet();
        source(id, "스냅샷", Role.INFLUENCER, UserStatus.SUSPENDED, 3L);

        ChatUserBackfillResult result = backfillService.backfill(id - 1, MAX_LIMIT);
        assertThat(result.created()).isEqualTo(1L);

        joined(id, "가입", Role.INFLUENCER, UserStatus.ACTIVE, 1L);
        modified(id, "옛변경", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 2L);
        awaitProcessed();

        assertThat(chatUserRepository.findById(id)).hasValueSatisfying(chatUser -> {
            assertThat(chatUser.getNickname()).isEqualTo("스냅샷");
            assertThat(chatUser.getStatus()).isEqualTo(UserStatus.SUSPENDED);
            assertThat(chatUser.getMemberVersion()).isEqualTo(3L);
        });
        assertThat(jdbcTemplate.queryForObject("select count(*) from chats.chat_user where id = ?", Long.class, id))
                .isEqualTo(1L);
        // 백필 생성과 옛 이벤트 건너뜀은 제어 작업을 남기지 않는다
        assertThat(controlRows()).isEmpty();
        assertThat(deadLetterKeys()).doesNotContain(String.valueOf(id));
    }

    @Test
    void 가입보다_먼저_와_DLT로_간_변경은_백필이_최신_버전으로_수렴시킨다() {
        long id = NEXT_ID.incrementAndGet();
        modified(id, "정지", Role.INFLUENCER, UserStatus.SUSPENDED, 2L);
        await().atMost(TIMEOUT).until(() -> deadLetterKeys().contains(String.valueOf(id)));
        joined(id, "가입", Role.INFLUENCER, UserStatus.ACTIVE, 1L);
        await().atMost(TIMEOUT).until(() -> chatUserRepository.findById(id).isPresent());
        assertThat(chatUserRepository.findById(id)).get().extracting(ChatUser::canChat).isEqualTo(true);
        source(id, "정지", Role.INFLUENCER, UserStatus.SUSPENDED, 2L);

        ChatUserBackfillResult result = backfillService.backfill(id - 1, MAX_LIMIT);

        assertThat(result).extracting(ChatUserBackfillResult::updated, ChatUserBackfillResult::completed)
                .containsExactly(1L, true);
        assertThat(chatUserRepository.findById(id)).hasValueSatisfying(chatUser -> {
            assertThat(chatUser.getStatus()).isEqualTo(UserStatus.SUSPENDED);
            assertThat(chatUser.getMemberVersion()).isEqualTo(2L);
        });
        assertThat(controlRows()).extracting(row -> row.get("user_id"), row -> row.get("member_version"),
                row -> row.get("reason")).containsExactly(tuple(id, 2L, "MEMBER_BACKFILL"));
    }

    @Test
    void 백필_반영_중_제어_작업_기록_뒤_실패하면_회원_갱신도_기록도_남지_않고_실패로_집계되며_다시_실행하면_반영된다() {
        long id = NEXT_ID.incrementAndGet();
        chatUserSyncService.syncJoined(new UserDto(id, "가입", Role.INFLUENCER, UserStatus.ACTIVE, 1L));
        source(id, "정지", Role.INFLUENCER, UserStatus.SUSPENDED, 2L);
        connectionControl.failAfterRecording(id);

        ChatUserBackfillResult failed = backfillService.backfill(id - 1, MAX_LIMIT);

        assertThat(failed).extracting(ChatUserBackfillResult::updated, ChatUserBackfillResult::failed,
                        ChatUserBackfillResult::failedIds, ChatUserBackfillResult::completed)
                .containsExactly(0L, 1L, List.of(id), false);
        // 충돌이 아니라 다시 시도하지 않는다(한 번 기록했다가 롤백)
        assertThat(connectionControl.calls(id)).isEqualTo(1L);
        assertThat(chatUserRepository.findById(id)).hasValueSatisfying(chatUser -> {
            assertThat(chatUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(chatUser.getMemberVersion()).isEqualTo(1L);
        });
        assertThat(controlRows()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("select count(*) from chats.chat_publish_checkpoint", Long.class))
                .isZero();

        connectionControl.reset();
        ChatUserBackfillResult retried = backfillService.backfill(id - 1, MAX_LIMIT);

        assertThat(retried).extracting(ChatUserBackfillResult::updated, ChatUserBackfillResult::completed)
                .containsExactly(1L, true);
        assertThat(controlRows()).extracting(row -> row.get("user_id"), row -> row.get("member_version"))
                .containsExactly(tuple(id, 2L));
        assertThat(jdbcTemplate.queryForObject("select count(*) from chats.chat_publish_checkpoint", Long.class))
                .isEqualTo(1L);
    }


    @Test
    void 가입_이벤트가_백필과_동시에_만들다_충돌하면_다시_처리해_한_행으로_수렴하고_DLT로_보내지_않는다() {
        long id = NEXT_ID.incrementAndGet();
        source(id, "스냅샷", Role.INFLUENCER, UserStatus.SUSPENDED, 2L);
        AtomicBoolean injected = new AtomicBoolean();
        // Kafka 가입 처리가 없다고 읽은 직후, 백필이 다른 트랜잭션에서 먼저 만들고 커밋한다
        AtomicLong listenerReads = interleaveOnListenerRead(id, injected,
                () -> backfillService.backfill(id - 1, MAX_LIMIT));

        joined(id, "가입", Role.INFLUENCER, UserStatus.ACTIVE, 1L);
        awaitProcessed();

        assertThat(injected).isTrue();
        // 첫 처리는 충돌로 롤백되고, 리스너가 같은 레코드를 다시 처리하며 다시 읽었다
        assertThat(listenerReads).hasValueGreaterThanOrEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("select count(*) from chats.chat_user where id = ?", Long.class, id))
                .isEqualTo(1L);
        assertThat(chatUserRepository.findById(id)).hasValueSatisfying(chatUser -> {
            assertThat(chatUser.getStatus()).isEqualTo(UserStatus.SUSPENDED);
            assertThat(chatUser.getMemberVersion()).isEqualTo(2L);
        });
        assertThat(controlRows()).isEmpty();
        assertThat(deadLetterKeys()).doesNotContain(String.valueOf(id));
    }

    @Test
    void 변경_이벤트가_백필_갱신과_동시에_고치다_충돌하면_다시_읽어_더_새로운_버전을_반영한다() {
        long id = NEXT_ID.incrementAndGet();
        chatUserSyncService.syncJoined(new UserDto(id, "가입", Role.INFLUENCER, UserStatus.ACTIVE, 1L));
        source(id, "스냅샷", Role.INFLUENCER, UserStatus.SUSPENDED, 2L);
        AtomicBoolean injected = new AtomicBoolean();
        // Kafka 변경 처리가 v1 복제본을 읽은 직후, 백필이 v2로 갱신하고 커밋한다(낙관적 락 버전이 바뀐다)
        AtomicLong listenerReads = interleaveOnListenerRead(id, injected,
                () -> backfillService.backfill(id - 1, MAX_LIMIT));

        modified(id, "최신", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 3L);
        awaitProcessed();

        assertThat(injected).isTrue();
        // 첫 처리는 충돌로 롤백되고, 리스너가 같은 레코드를 다시 처리하며 다시 읽었다
        assertThat(listenerReads).hasValueGreaterThanOrEqualTo(2L);
        assertThat(chatUserRepository.findById(id)).hasValueSatisfying(chatUser -> {
            assertThat(chatUser.getNickname()).isEqualTo("최신");
            assertThat(chatUser.getMemberVersion()).isEqualTo(3L);
            assertThat(chatUser.canChat()).isTrue();
        });
        // 백필 갱신(v2, 채팅 불가)만 기록되고, 충돌로 롤백된 Kafka 첫 처리의 흔적은 없다
        assertThat(controlRows()).extracting(row -> row.get("user_id"), row -> row.get("member_version"),
                row -> row.get("reason")).containsExactly(tuple(id, 2L, "MEMBER_BACKFILL"));
        assertThat(deadLetterKeys()).doesNotContain(String.valueOf(id));
    }

    /** Kafka 리스너 스레드가 이 회원을 처음 읽은 직후(그 트랜잭션 안에서) 다른 스레드의 작업을 끝까지 실행한다 */
    private AtomicLong interleaveOnListenerRead(long id, AtomicBoolean injected, Runnable other) {
        Answer<?> real = mockingDetails(chatUserRepository).getMockCreationSettings().getDefaultAnswer();
        AtomicLong listenerReads = new AtomicLong();
        doAnswer(invocation -> {
            Object read = real.answer(invocation);
            boolean listenerRead = Long.valueOf(id).equals(invocation.getArgument(0))
                    && Thread.currentThread().getName().startsWith("chat-member-events");
            if (listenerRead) {
                listenerReads.incrementAndGet();
            }
            if (listenerRead && injected.compareAndSet(false, true)) {
                CompletableFuture.runAsync(other).get(20, TimeUnit.SECONDS);
            }
            return read;
        }).when(chatUserRepository).findById(any());
        return listenerReads;
    }

    // ---------------------------------------------------------------------------------------------

    private static void source(long id, String nickname, Role role, UserStatus status, Long version) {
        MEMBERS.put(id, new UserDto(id, nickname, role, status, version));
    }

    private void joined(long id, String nickname, Role role, UserStatus status, Long version) {
        send(UserJoinedEvent.class.getName(), id, userJson(id, nickname, role, status, version));
    }

    private void modified(long id, String nickname, Role role, UserStatus status, Long version) {
        send(UserModifiedEvent.class.getName(), id, userJson(id, nickname, role, status, version));
    }

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
        long marker = NEXT_ID.addAndGet(1_000);
        joined(marker, "표식", Role.USER, UserStatus.ACTIVE, 0L);
        await().atMost(TIMEOUT).until(() -> chatUserRepository.findById(marker).isPresent());
    }

    private long memberVersion(long id) {
        Long version = jdbcTemplate.queryForObject("select member_version from chats.chat_user where id = ?",
                Long.class, id);
        return version == null ? -1 : version;
    }

    private Set<String> deadLetterKeys() {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        Set<String> keys = ConcurrentHashMap.newKeySet();
        TopicPartition dlt = new TopicPartition(DLT, 0);
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
                new ByteArrayDeserializer())) {
            consumer.assign(List.of(dlt));
            consumer.seekToBeginning(List.of(dlt));
            long end = consumer.endOffsets(List.of(dlt)).get(dlt);
            await().atMost(TIMEOUT).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> keys.add(record.key()));
                return consumer.position(dlt) >= end;
            });
        }
        return keys;
    }

    private List<Map<String, Object>> controlRows() {
        return jdbcTemplate.queryForList(
                "select user_id, member_version, reason from chats.chat_control_outbox order by user_id");
    }

    private void clearControlTables() {
        jdbcTemplate.update("delete from chats.chat_publish_checkpoint");
        jdbcTemplate.update("delete from chats.chat_control_outbox");
    }

    private static String json(UserDto user) {
        return "{\"id\":" + user.id() + ",\"nickname\":\"" + user.nickname() + "\",\"role\":\"" + user.role().name()
                + "\",\"status\":\"" + user.status().name() + "\",\"version\":" + user.version() + "}";
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/internal/members", exchange -> {
                Map<String, String> query = new HashMap<>();
                String raw = exchange.getRequestURI().getRawQuery();
                for (String pair : raw == null ? new String[0] : raw.split("&")) {
                    String[] kv = pair.split("=", 2);
                    query.put(kv[0], kv.length > 1 ? kv[1] : "");
                }
                long afterId = query.get("afterId") == null ? 0 : Long.parseLong(query.get("afterId"));
                int size = Integer.parseInt(query.get("size"));
                // 페이지는 훅보다 먼저 읽는다: 훅이 반영한 변경은 이 페이지에 없다(옛 스냅샷)
                List<UserDto> rest = new ArrayList<>(MEMBERS.tailMap(afterId, false).values());
                List<UserDto> page = rest.subList(0, Math.min(size, rest.size()));
                StringBuilder items = new StringBuilder();
                for (UserDto user : page) {
                    items.append(items.isEmpty() ? "" : ",").append(json(user));
                }
                String body = "{\"statusCode\":200,\"message\":\"ok\",\"data\":{\"items\":[" + items + "]"
                        + (rest.size() > size ? ",\"nextAfterId\":" + page.getLast().id() : "") + "}}";
                LongConsumer hook = beforeRespond;
                if (hook != null) {
                    hook.accept(afterId);
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 이 클래스 전용 토픽과 DLT. 리스너 컨테이너가 시작하기 전에 KafkaAdmin이 만든다 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic chatUserBackfillRaceTopic() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }

        @Bean
        NewTopic chatUserBackfillRaceDeadLetterTopic() {
            return TopicBuilder.name(DLT).partitions(1).replicas(1).build();
        }
    }

    /** 실제 제어 작업 기록을 감싸 호출 수를 세고, 지정한 회원은 기록한 뒤 같은 트랜잭션 안에서 실패시킨다 */
    @Primary
    @TestComponent
    static class FailableConnectionControl implements ChatConnectionControl {

        private final JdbcChatConnectionControl delegate;
        private final Map<Long, AtomicLong> calls = new ConcurrentHashMap<>();
        private final Set<Long> failing = ConcurrentHashMap.newKeySet();

        FailableConnectionControl(JdbcChatConnectionControl delegate) {
            this.delegate = delegate;
        }

        @Override
        public void requestRecheck(Long userId, Long memberVersion, ChatControlReason reason) {
            calls.computeIfAbsent(userId, key -> new AtomicLong()).incrementAndGet();
            delegate.requestRecheck(userId, memberVersion, reason);
            if (failing.contains(userId)) {
                throw new IllegalStateException("제어 작업 기록 뒤 실패(테스트)");
            }
        }

        void failAfterRecording(Long userId) {
            failing.add(userId);
        }

        long calls(Long userId) {
            AtomicLong count = calls.get(userId);
            return count == null ? 0 : count.get();
        }

        void reset() {
            calls.clear();
            failing.clear();
        }
    }
}
