package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static com.hapbang.chat.in.support.RealtimeStompClient.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;

import com.hapbang.chat.app.ChatDeliveryQueue;
import com.hapbang.chat.app.ChatRoomUseCase;
import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.LocalChatConnections;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.fixture.ChatNativeSql;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.fixture.FixedPortRedis;
import com.hapbang.chat.in.dto.SendChatMessageRequest;
import com.hapbang.chat.in.support.ChatE2EDatabaseCleanUpListener;
import com.hapbang.chat.in.support.ChatPipelineIsolationListener;
import com.hapbang.chat.in.support.E2ELoginConfig;
import com.hapbang.chat.in.support.RealtimeStompClient;
import com.hapbang.chat.in.support.RealtimeStompClient.Frame;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.ModuleTestConfiguration;
import com.hapbang.testsupport.PostgresTestcontainersConfig;

import jakarta.persistence.EntityManager;

/**
 * 통합 검증(2026-10-30 사용자 결정: Redis 인프라 장애는 작업의 시도가 아니다)을 실제 STOMP와 스케줄러가 돌리는 전달 파이프라인으로
 * 끝까지 확인한다. {@link ChatRedisOutageE2ETest}는 짧은 단절 한 번을, {@code ChatDeliveryRedisOutageTest}는 긴 단절을 직접 호출로
 * 확인한다. 이 클래스는 긴 단절과 쓰기 거절(OOM)을 실제 연결로 본다.
 * <p>
 * 적재·발행 시도 상한을 2, backoff를 1ms로 줄여, 장애를 시도로 셌다면 1초 안에 격리(DEAD)됐을 설정에서 Redis를 그보다 수십 배 긴
 * 동안 멈춘다(운영 기본값 기준 옛 시도 예산 약 4분을 넘는 단절과 같은 조건). 재확인 간격도 줄여 단절 동안 재확인이 여러 번 실패하게 한다.
 */
@SpringBootTest(classes = ModuleTestConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.name=hapbang-module-test",
                "spring.modulith.events.jdbc.schema-initialization.enabled=true",
                "spring.modulith.events.completion-mode=delete",
                "spring.security.oauth2.client.registration.google.client-id=test",
                "spring.security.oauth2.client.registration.google.client-secret=test",
                "hapbang.internal-api.key=" + IntegrationTest.INTERNAL_API_KEY,
                "hapbang.chat.websocket.allowed-origins=http://localhost:5173",
                "hapbang.chat.realtime.enabled=true",
                "hapbang.chat.delivery.pipeline.enabled=true",
                "hapbang.scheduling.enabled=true",
                // 장애를 시도로 셌다면 곧바로 격리됐을 상한·간격
                "hapbang.chat.delivery.relay.max-attempts=2",
                "hapbang.chat.delivery.relay.backoff.initial=1ms",
                "hapbang.chat.delivery.relay.backoff.max=1ms",
                "hapbang.chat.delivery.relay.backoff.jitter=0",
                "hapbang.chat.delivery.consumer.max-attempts=2",
                "hapbang.chat.delivery.consumer.backoff.initial=1ms",
                "hapbang.chat.delivery.consumer.backoff.max=1ms",
                "hapbang.chat.delivery.consumer.backoff.jitter=0",
                "hapbang.chat.delivery.consumer.block=200ms",
                "hapbang.chat.delivery.reclaim.interval=200ms",
                "hapbang.chat.delivery.reclaim.min-idle=1s",
                "hapbang.chat.delivery.reconstruct.interval=1s",
                "hapbang.chat.delivery.reconstruct.enqueued-threshold=3s",
                "hapbang.chat.delivery.reconstruct.cooldown=1ms",
                // 재확인 간격: 50ms에서 시작해 200ms에서 멈춘다(단절 동안 재확인 수십 번)
                "hapbang.chat.delivery.pause.probe-initial=50ms",
                "hapbang.chat.delivery.pause.probe-max=200ms",
                "hapbang.chat.realtime.listener.recovery-interval=200ms",
                "spring.data.redis.timeout=500ms"
        })
@Import({PostgresTestcontainersConfig.class, E2ELoginConfig.class, E2ELoginConfig.TestSessionLoginController.class})
@DirtiesContext
@TestExecutionListeners(listeners = {ChatE2EDatabaseCleanUpListener.class, ChatPipelineIsolationListener.class},
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class ChatRedisLongOutageE2ETest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    /** 옛 동작(시도 상한 2, backoff 1ms)이면 1초 안에 격리됐을 단절을 이만큼 유지한다 */
    private static final Duration LONG_OUTAGE = Duration.ofSeconds(20);
    private static final String MESSAGES = "/user/queue/chats/messages";

    /** 장애 시험 전용 Redis. 죽였다 다시 띄워도 같은 주소로 붙도록 호스트 포트를 고정한다(포트 충돌이면 새 포트로 다시 띄운다) */
    private static final FixedPortRedis DEDICATED = FixedPortRedis.start("redis-server", "--appendonly", "yes",
            "--appendfsync", "always", "--maxmemory-policy", "noeviction");
    private static final GenericContainer<?> REDIS = DEDICATED.container();
    private static final int HOST_PORT = DEDICATED.hostPort();

    @DynamicPropertySource
    static void dedicatedRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> HOST_PORT);
        registry.add("hapbang.chat.redis.namespace", () -> "test-redis-long-outage-" + UUID.randomUUID());
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private LocalChatConnections localChatConnections;

    private ChatNativeSql sql;

    @Autowired
    private StringRedisTemplate redis;

    /** 재확인(PING) 횟수를 세려고 실제 큐를 감싼다 */
    @MockitoSpyBean
    private ChatDeliveryQueue chatDeliveryQueue;

    private RealtimeStompClient stomp;

    @Autowired
    void setUpSql(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        sql = ChatNativeSql.of(entityManager, transactionManager);
    }

    @BeforeEach
    void setUp() {
        if (!isRedisRunning()) {
            startRedis();
        }
        awaitRedisReady();
        setMaxMemory("0");
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        stomp = new RealtimeStompClient(RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build(),
                port, localChatConnections);
    }

    @AfterEach
    void tearDown() {
        if (!isRedisRunning()) {
            startRedis();
        }
        awaitRedisReady();
        setMaxMemory("0");
        stomp.close();
    }

    @Test
    void Redis가_옛_시도_예산보다_훨씬_오래_멈춰도_장애_중_메시지는_격리되지_않고_시도_0으로_남았다가_복구_뒤_실제_STOMP로_한_번씩_전달된다()
            throws Exception {
        Long chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        StompSession host = stomp.connect(HOST_ID);
        StompSession guest = stomp.connect(GUEST_ID);
        BlockingQueue<Frame<ChatMessageResponse>> hostMessages = stomp.subscribe(host, MESSAGES,
                ChatMessageResponse.class);
        BlockingQueue<Frame<ChatMessageResponse>> guestMessages = stomp.subscribeRoomMessages(guest, chatRoomId, 1);
        waitForSubscriptions();
        host.send(sendPath(chatRoomId), new SendChatMessageRequest("장애 전", "before"));
        assertThat(poll(guestMessages).payload().content()).isEqualTo("장애 전");
        assertThat(poll(hostMessages).payload().content()).isEqualTo("장애 전");
        awaitAllPublished();
        long attemptsBefore = attemptSum();

        killRedis();
        for (int i = 1; i <= 3; i++) {
            host.send(sendPath(chatRoomId), new SendChatMessageRequest("장애 중 " + i, "during-" + i));
        }
        await().atMost(WAIT).until(() -> savedCount(chatRoomId) == 4);
        // 단절을 알아채기 전 relay가 선점했을 수 있다. 연결 불가로 선점을 시도 없이 되돌리면 그 뒤로는 재확인만 한다
        await().atMost(WAIT).until(() -> pendingDuringOutage() == 3);
        int pingsBefore = pingCount();
        long outageEndsAt = System.nanoTime() + LONG_OUTAGE.toNanos();
        while (System.nanoTime() < outageEndsAt) {
            // 단절 내내 어떤 작업도 격리되지 않고, 장애 중 메시지의 적재 시도는 0으로 남는다
            assertThat(deadCount()).isZero();
            assertThat(pendingDuringOutage()).isEqualTo(3);
            Thread.sleep(500);
        }
        int probes = pingCount() - pingsBefore;
        assertThat(probes).as("단절 동안 재확인(PING)이 여러 번 실패했다").isGreaterThanOrEqualTo(20);
        assertThat(deadCount()).isZero();
        assertThat(attemptSum()).isEqualTo(attemptsBefore);
        assertThat(guestMessages).isEmpty();
        assertThat(List.of(host, guest)).allMatch(StompSession::isConnected);

        startRedis();

        List<ChatMessageResponse> toGuest = collect(guestMessages, 3);
        List<ChatMessageResponse> toHost = collect(hostMessages, 3);
        // 한 번씩만: 더 지켜봐도 중복이 오지 않는다
        Thread.sleep(2000);
        drainInto(guestMessages, toGuest);
        drainInto(hostMessages, toHost);
        // 상대가 받는 메시지에는 전송 ID가 없다(보낸 회원에게만 보인다). 내용으로 본다
        assertThat(toGuest).extracting(ChatMessageResponse::content)
                .containsExactlyInAnyOrder("장애 중 1", "장애 중 2", "장애 중 3");
        assertThat(toGuest).extracting(ChatMessageResponse::roomSequence).containsExactlyInAnyOrder(2L, 3L, 4L);
        assertThat(toHost).extracting(ChatMessageResponse::clientMessageId)
                .containsExactlyInAnyOrder("during-1", "during-2", "during-3");
        awaitAllPublished();
        assertThat(deadCount()).isZero();
        // 복구 뒤 성공한 적재 한 번씩만 시도로 남는다
        assertThat(sql.list("""
                select o.attempt_count from chats.chat_outbox o join chats.chat_message m on m.id = o.message_id
                where m.client_message_id like 'during-%'""", Integer.class)).containsOnly(1);

        host.send(sendPath(chatRoomId), new SendChatMessageRequest("복구 뒤", "after"));
        assertThat(poll(guestMessages).payload().content()).isEqualTo("복구 뒤");
        assertThat(List.of(host, guest)).allMatch(StompSession::isConnected);
    }

    @Test
    void Redis가_메모리_상한으로_쓰기를_거절하는_동안_보낸_메시지는_격리되지_않고_시도_0으로_남았다가_상한이_풀리면_실제_STOMP로_한_번씩_전달된다()
            throws Exception {
        Long chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        StompSession host = stomp.connect(HOST_ID);
        StompSession guest = stomp.connect(GUEST_ID);
        BlockingQueue<Frame<ChatMessageResponse>> guestMessages = stomp.subscribeRoomMessages(guest, chatRoomId, 1);
        waitForSubscriptions();
        host.send(sendPath(chatRoomId), new SendChatMessageRequest("상한 전", "before"));
        assertThat(poll(guestMessages).payload().content()).isEqualTo("상한 전");
        awaitAllPublished();

        setMaxMemory("1");
        assertThatThrownBy(() -> redis.opsForList().leftPush("oom-check", "x")).rootCause()
                .hasMessageContaining("OOM");
        for (int i = 1; i <= 2; i++) {
            host.send(sendPath(chatRoomId), new SendChatMessageRequest("상한 중 " + i, "oom-" + i));
        }
        await().atMost(WAIT).until(() -> savedCount(chatRoomId) == 3);
        // 재확인 적재가 여러 번 거절되는 동안(상한 200ms) 격리되지 않고 시도로 세지 않는다
        long until = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        while (System.nanoTime() < until) {
            assertThat(deadCount()).isZero();
            Thread.sleep(300);
        }
        // 재확인 적재가 한 건을 잠깐 선점(PROCESSING)할 수 있으므로 PENDING·시도 0으로 돌아온 순간을 본다
        await().atMost(WAIT).until(() -> pendingOom() == 2);
        assertThat(guestMessages.poll(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(List.of(host, guest)).allMatch(StompSession::isConnected);

        setMaxMemory("0");

        List<ChatMessageResponse> toGuest = collect(guestMessages, 2);
        Thread.sleep(2000);
        drainInto(guestMessages, toGuest);
        assertThat(toGuest).extracting(ChatMessageResponse::content).containsExactlyInAnyOrder("상한 중 1", "상한 중 2");
        awaitAllPublished();
        assertThat(deadCount()).isZero();
        assertThat(sql.list("""
                select o.attempt_count from chats.chat_outbox o join chats.chat_message m on m.id = o.message_id
                where m.client_message_id like 'oom-%'""", Integer.class)).containsOnly(1);
    }

    private List<ChatMessageResponse> collect(BlockingQueue<Frame<ChatMessageResponse>> queue, int expected) {
        List<ChatMessageResponse> received = new ArrayList<>();
        await().atMost(WAIT).until(() -> {
            drainInto(queue, received);
            return received.size() >= expected;
        });
        return received;
    }

    private static void drainInto(BlockingQueue<Frame<ChatMessageResponse>> queue, List<ChatMessageResponse> into) {
        Frame<ChatMessageResponse> frame;
        while ((frame = queue.poll()) != null) {
            into.add(frame.payload());
        }
    }

    private int pingCount() {
        return (int) mockingDetails(chatDeliveryQueue).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("ping"))
                .count();
    }

    private long savedCount(Long chatRoomId) {
        return sql.single("select count(*) from chats.chat_message where chat_room_id = :roomId", Long.class,
                Map.of("roomId", chatRoomId));
    }

    private long pendingDuringOutage() {
        return sql.single("""
                select count(*) from chats.chat_outbox o join chats.chat_message m on m.id = o.message_id
                where m.client_message_id like 'during-%' and o.status = 'PENDING' and o.attempt_count = 0
                  and o.total_attempt_count = 0""", Long.class);
    }

    private long pendingOom() {
        return sql.single("""
                select count(*) from chats.chat_outbox o join chats.chat_message m on m.id = o.message_id
                where m.client_message_id like 'oom-%' and o.status = 'PENDING' and o.attempt_count = 0
                  and o.total_attempt_count = 0""", Long.class);
    }

    private void awaitAllPublished() {
        await().atMost(WAIT).until(() -> sql.single(
                "select count(*) from chats.chat_publish_checkpoint where status <> 'PUBLISHED'", Long.class) == 0);
    }

    private long deadCount() {
        return sql.single("""
                select (select count(*) from chats.chat_publish_checkpoint where status = 'DEAD')
                     + (select count(*) from chats.chat_outbox where status = 'DEAD')""", Long.class);
    }

    private long attemptSum() {
        return sql.single("""
                select (select coalesce(sum(attempt_count + total_attempt_count), 0)
                        from chats.chat_publish_checkpoint)
                     + (select coalesce(sum(attempt_count + total_attempt_count + requeue_count), 0)
                        from chats.chat_outbox)""", Long.class);
    }

    private static <T> Frame<T> poll(BlockingQueue<Frame<T>> queue) throws InterruptedException {
        Frame<T> frame = queue.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(frame).isNotNull();
        return frame;
    }

    private static String sendPath(Long chatRoomId) {
        return "/pub/chats/" + chatRoomId + "/messages";
    }

    private void setMaxMemory(String value) {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().setConfig("maxmemory", value);
            return null;
        });
    }

    private void awaitRedisReady() {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).ignoreExceptions().until(() -> {
            redis.hasKey("ready-check");
            return true;
        });
    }

    private static boolean isRedisRunning() {
        return Boolean.TRUE.equals(REDIS.getDockerClient().inspectContainerCmd(REDIS.getContainerId()).exec()
                .getState().getRunning());
    }

    private static void killRedis() {
        REDIS.getDockerClient().killContainerCmd(REDIS.getContainerId()).exec();
    }

    private static void startRedis() {
        REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec();
    }

}
