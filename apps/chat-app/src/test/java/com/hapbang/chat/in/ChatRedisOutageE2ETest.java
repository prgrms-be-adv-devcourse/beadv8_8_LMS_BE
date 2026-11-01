package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static com.hapbang.chat.in.support.RealtimeStompClient.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;

import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.LocalChatConnections;
import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.fixture.ChatNativeSql;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.fixture.FixedPortRedis;
import com.hapbang.chat.global.response.RsData;
import com.hapbang.chat.in.dto.ChatErrorMessage;
import com.hapbang.chat.in.dto.ChatNotification;
import com.hapbang.chat.in.dto.CreateChatRoomRequest;
import com.hapbang.chat.in.dto.SendChatMessageRequest;
import com.hapbang.chat.in.support.ChatE2EDatabaseCleanUpListener;
import com.hapbang.chat.in.support.ChatPipelineIsolationListener;
import com.hapbang.chat.in.support.E2ELoginConfig;
import com.hapbang.chat.in.support.E2ESession;
import com.hapbang.chat.in.support.RealtimeStompClient;
import com.hapbang.chat.in.support.RealtimeStompClient.Frame;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.ModuleTestConfiguration;
import com.hapbang.testsupport.PostgresTestcontainersConfig;

import jakarta.persistence.EntityManager;

/**
 * 설계 9장 "broker·Redis 장애"의 Redis 부분을 실제 STOMP까지 확인한다: Redis가 멈춘 동안 보낸 메시지는 DB에 저장되고 outbox에
 * 남으며, Redis가 돌아오면 relay가 다시 적재해 실제 STOMP 연결에 한 번씩 전달된다. 장애 전 연결은 유지된다.
 * <p>
 * 공용 Redis 컨테이너를 멈추지 않도록 이 클래스만 쓰는 Redis 컨테이너를 띄운다(운영 후보 설정 AOF·noeviction,
 * {@link com.hapbang.chat.app.ChatDeliveryRedisFailureTest}와 같다). 재시작 뒤 같은 주소로 다시 붙도록 호스트 포트를 고정한다.
 * {@code @ChatE2ETest}는 공용 Redis 설정을 Import하므로 같은 설정을 이 클래스에 직접 적고 Redis 주소만 바꾼다.
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
                // 장애 중 relay 시도가 상한(DEAD)에 닿지 않고 복구 뒤 곧 다시 시도하도록 backoff를 줄인다
                "hapbang.chat.delivery.relay.backoff.initial=200ms",
                "hapbang.chat.delivery.relay.backoff.max=1s",
                "hapbang.chat.delivery.relay.backoff.jitter=0",
                "hapbang.chat.delivery.consumer.block=200ms",
                "hapbang.chat.realtime.listener.recovery-interval=500ms",
                // 장애 중 명령이 연결 복구를 기다리며 오래 멈추지 않게 한다(운영 기본값은 Lettuce 60초)
                "spring.data.redis.timeout=2s"
        })
@Import({PostgresTestcontainersConfig.class, E2ELoginConfig.class, E2ELoginConfig.TestSessionLoginController.class})
@DirtiesContext
@TestExecutionListeners(listeners = {ChatE2EDatabaseCleanUpListener.class, ChatPipelineIsolationListener.class},
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class ChatRedisOutageE2ETest {

    private static final Duration WAIT = Duration.ofSeconds(30);
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
        registry.add("hapbang.chat.redis.namespace", () -> "test-redis-outage-" + UUID.randomUUID());
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
    private LocalChatConnections localChatConnections;

    private ChatNativeSql sql;

    @Autowired
    private StringRedisTemplate redis;

    private RestTestClient client;
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
        assertThat(localChatConnections.connectedUserIds()).isEmpty();
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        stomp = new RealtimeStompClient(client, port, localChatConnections);
    }

    @AfterEach
    void tearDown() {
        if (!isRedisRunning()) {
            startRedis();
        }
        stomp.close();
    }

    @Test
    void Redis가_멈춘_동안_보낸_메시지는_저장되고_outbox에_남았다가_Redis가_돌아오면_실제_STOMP로_한_번씩_전달된다()
            throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession host = stomp.connect(HOST_ID);
        StompSession guest = stomp.connect(GUEST_ID);
        StompSession guestElsewhere = stomp.connect(GUEST_ID);
        BlockingQueue<Frame<ChatMessageResponse>> hostMessages = stomp.subscribe(host, MESSAGES,
                ChatMessageResponse.class);
        // 게스트는 방을 열어 둔 연결(방 topic)과 열지 않은 연결(새 메시지 알림)을 함께 둔다
        BlockingQueue<Frame<Map>> guestMessages = stomp.subscribeRoom(guest, chatRoomId, 1).frames();
        BlockingQueue<Frame<ChatNotification>> guestNotifications = stomp.subscribe(guestElsewhere,
                "/user/queue/chats/notifications", ChatNotification.class);
        BlockingQueue<Frame<ChatErrorMessage>> hostErrors = stomp.subscribe(host, "/user/queue/chats/errors",
                ChatErrorMessage.class);
        waitForSubscriptions();

        host.send(sendPath(chatRoomId), new SendChatMessageRequest("장애 전", "before"));
        assertThat(poll(guestMessages).payload()).containsEntry("content", "장애 전");
        poll(hostMessages);
        poll(guestNotifications);
        awaitAllPublished();

        killRedis();
        // 전송 경로(저장 트랜잭션)는 Redis를 쓰지 않는다: 오류 없이 저장되고, 적재 실패는 outbox에 남는다
        host.send(sendPath(chatRoomId), new SendChatMessageRequest("장애 중", "during"));
        // 적재 실패가 기록될 때까지 기다린다(실패 기록은 last_error로 본다)
        await().atMost(WAIT).until(() -> outboxOf("장애 중") != null && outboxOf("장애 중").get("last_error") != null);
        Map<String, Object> outboxDuringOutage = outboxOf("장애 중");
        // 짧은 backoff라 다음 시도를 선점한 순간(PROCESSING)일 수 있다. 어느 쪽이든 적재되지 않고 outbox에 남아 있다
        assertThat(outboxDuringOutage.get("status")).isIn("PENDING", "PROCESSING");
        // Redis 연결 불가는 작업의 시도가 아니다: relay가 선점을 풀고 시도 횟수를 올리지 않는다(2026-10-30 사용자 결정)
        assertThat(((Number) outboxDuringOutage.get("attempt_count")).intValue()).isZero();
        assertThat(checkpointStatusOf("장애 중")).isEqualTo("PENDING");
        assertThat(hostErrors).isEmpty();
        assertThat(guestMessages).isEmpty();
        assertThat(List.of(host, guest)).allMatch(StompSession::isConnected);
        assertThat(messageContents(chatRoomId, GUEST_ID)).containsExactly("장애 중", "장애 전");

        startRedis();

        // Redis가 돌아오면 컨테이너가 서버 채널과 방 채널을 다시 구독하고, 재적재된 작업이 방 topic으로 간다
        Frame<Map> toGuest = guestMessages.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(toGuest).as("Redis 복구 뒤 재적재·전달").isNotNull();
        assertThat(toGuest.payload()).containsEntry("content", "장애 중").containsEntry("roomSequence", 2);
        assertThat(toGuest.participationVersion()).isEqualTo("1");
        Frame<ChatMessageResponse> toHost = poll(hostMessages);
        assertThat(toHost.payload().clientMessageId()).isEqualTo("during");
        assertThat(poll(guestNotifications).payload().lastSequence()).isEqualTo(2);
        awaitAllPublished();
        assertThat(outboxOf("장애 중").get("status")).isEqualTo("ENQUEUED");

        // 복구 뒤 새 전송도 평소처럼 간다. 장애 중 메시지는 다시 오지 않는다
        host.send(sendPath(chatRoomId), new SendChatMessageRequest("복구 뒤", "after"));
        assertThat(poll(guestMessages).payload()).containsEntry("content", "복구 뒤");
        assertThat(poll(hostMessages).payload().content()).isEqualTo("복구 뒤");
        Thread.sleep(1000);
        assertThat(guestMessages).isEmpty();
        assertThat(hostMessages).isEmpty();
        assertThat(hostErrors).isEmpty();
        assertThat(List.of(host, guest, guestElsewhere)).allMatch(StompSession::isConnected);
        assertThat(sql.single("select count(*) from chats.chat_message", Long.class)).isEqualTo(3);
    }

    private Map<String, Object> outboxOf(String content) {
        List<Map<String, Object>> rows = sql.rows("""
                select o.status, o.attempt_count, o.last_error from chats.chat_outbox o
                join chats.chat_message m on m.id = o.message_id where m.content = :content""",
                Map.of("content", content));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String checkpointStatusOf(String content) {
        return sql.single("""
                select c.status from chats.chat_publish_checkpoint c
                join chats.chat_outbox o on o.delivery_id = c.job_id
                join chats.chat_message m on m.id = o.message_id where m.content = :content""", String.class,
                Map.of("content", content));
    }

    private void awaitAllPublished() {
        await().atMost(WAIT).until(() -> sql.single(
                "select count(*) from chats.chat_publish_checkpoint where status <> 'PUBLISHED'", Long.class) == 0);
    }

    private static <T> Frame<T> poll(BlockingQueue<Frame<T>> queue) throws InterruptedException {
        Frame<T> frame = queue.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(frame).isNotNull();
        return frame;
    }

    private static String sendPath(Long chatRoomId) {
        return "/pub/chats/" + chatRoomId + "/messages";
    }

    private Long createRoom(Long userId, Long targetUserId) {
        RsData<ChatRoomResponse> room = client.post().uri("/api/v1/chats")
                .headers(E2ESession.login(client, userId, Role.INFLUENCER)::applyTo)
                .body(new CreateChatRoomRequest(targetUserId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(new ParameterizedTypeReference<RsData<ChatRoomResponse>>() {
                })
                .returnResult().getResponseBody();
        assertThat(room).isNotNull();
        return room.data().chatRoomId();
    }

    private List<String> messageContents(Long chatRoomId, Long userId) {
        RsData<ChatMessagePageResponse> page = client.get().uri("/api/v1/chats/{id}/messages", chatRoomId)
                .header(HttpHeaders.COOKIE, E2ESession.login(client, userId, Role.INFLUENCER).cookieHeader())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<RsData<ChatMessagePageResponse>>() {
                })
                .returnResult().getResponseBody();
        assertThat(page).isNotNull();
        return page.data().messages().stream().map(ChatMessageResponse::content).toList();
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
