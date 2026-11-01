package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static com.hapbang.chat.in.support.RealtimeStompClient.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.hapbang.chat.app.ChatRoomUseCase;
import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.LocalChatConnections;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.fixture.ChatNativeSql;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.in.dto.SendChatMessageRequest;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.support.E2ESession;
import com.hapbang.chat.in.support.RealtimeStompClient;
import com.hapbang.chat.in.websocket.ChatInboundReceiveBudget;
import com.hapbang.shared.member.domain.Role;

import jakarta.persistence.EntityManager;

/**
 * 통합 재검증(2026-10-30): 운영 기본값(처리 스레드 8·공용 대기열 1000·연결별 몫 64프레임)에서 연결별 처리 줄이 연결 종료 뒤 남지 않는지
 * 실제 STOMP로 확인한다. 정상 끊기(DISCONNECT 프레임), 몫 초과로 서버가 닫음, 처리 대기 프레임을 남긴 채 웹소켓만 닫힘(STOMP DISCONNECT
 * 없이)을 모두 본다. 연결별 줄 목록은 내부 필드({@code queues})를 읽는다.
 */
@ChatE2ETest
class ChatInboundSessionQueueE2ETest {

    @LocalServerPort
    private int port;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private LocalChatConnections localChatConnections;

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    private ChatNativeSql sql;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ChatInboundReceiveBudget chatInboundReceiveBudget;

    private RealtimeStompClient stomp;
    private RestTestClient restTestClient;
    private final List<ApplicationListener<SessionDisconnectEvent>> listeners = new CopyOnWriteArrayList<>();

    @Autowired
    void setUpSql(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        sql = ChatNativeSql.of(entityManager, transactionManager);
    }

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        restTestClient = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        stomp = new RealtimeStompClient(restTestClient, port, localChatConnections);
        // 같은 컨텍스트의 앞 메서드가 남긴 줄은 이 메서드의 검사에서 뺀다
        baseline = java.util.Set.copyOf(queues().keySet());
    }

    private java.util.Set<String> baseline;

    /** 이 메서드에서 연 연결의 처리 줄이 모두 지워졌다 */
    private void assertNoNewSessionQueues() {
        try {
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .until(() -> baseline.containsAll(queues().keySet()));
        } catch (org.awaitility.core.ConditionTimeoutException leaked) {
            throw new AssertionError("연결 종료 뒤에도 남은 연결별 처리 줄: " + queues().keySet().stream()
                    .filter(id -> !baseline.contains(id)).toList(), leaked);
        }
    }

    @AfterEach
    void tearDown() {
        listeners.forEach(applicationContext::removeApplicationListener);
        listeners.clear();
        stomp.close();
    }

    @Test
    void 정상으로_연결하고_끊기를_반복해도_연결별_처리_줄이_남지_않는다() throws Exception {
        Long chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        for (int cycle = 0; cycle < 30; cycle++) {
            StompSession session = stomp.connect(cycle % 2 == 0 ? HOST_ID : GUEST_ID);
            stomp.subscribe(session, "/user/queue/chats/messages", ChatMessageResponse.class);
            session.send("/pub/chats/" + chatRoomId + "/messages", new SendChatMessageRequest("반복", "cycle-" + cycle));
            session.disconnect();
        }
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> localChatConnections.connectedUserIds().isEmpty());
        awaitInboundIdle();
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> clientMessageIds(chatRoomId).size() == 30);

        assertNoNewSessionQueues();
    }

    /**
     * 운영 기본 몫(64)에서 처리가 막힌 채 SEND 29·30·31개를 연달아 보내도(한 줄 비우기 단위 30 경계) 연결은 닫히지 않고 모두 보낸 순서대로
     * 저장된다.
     */
    @Test
    void 운영_기본_몫에서_SEND_29에서_31개를_연달아_보내도_닫히지_않고_모두_순서대로_저장된다() throws Exception {
        Long chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        Map<String, CloseStatus> closes = listenCloses();
        List<String> expected = new java.util.ArrayList<>();
        for (int count = 29; count <= 31; count++) {
            StompSession host = stomp.connect(HOST_ID);
            awaitInboundIdle();
            RoomLock lock = lockRoom(chatRoomId);
            try {
                for (int i = 1; i <= count; i++) {
                    String clientMessageId = "burst" + count + "-" + i;
                    host.send("/pub/chats/" + chatRoomId + "/messages", new SendChatMessageRequest("연달아", clientMessageId));
                    expected.add(clientMessageId);
                }
                // 모두 줄에 들어갈 때까지 처리를 막아 둔다
                Thread.sleep(300);
            } finally {
                lock.release();
            }
            awaitInboundIdle();
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> clientMessageIds(chatRoomId).size() == expected.size());
            assertThat(closes).as("%d개 연달아 보낸 연결", count).isEmpty();
            assertThat(host.isConnected()).isTrue();
            host.disconnect();
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> closes.containsKey(String.valueOf(HOST_ID)));
            assertThat(closes.get(String.valueOf(HOST_ID)).getReason()).isNotEqualTo("INBOUND_OVERFLOW");
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> localChatConnections.connectedUserIds().isEmpty());
            closes.clear();
        }
        assertThat(clientMessageIds(chatRoomId)).containsExactlyElementsOf(expected);
        assertNoNewSessionQueues();
    }

    @Test
    void 몫을_넘겨_서버가_닫은_연결의_처리_줄도_남지_않는다() throws Exception {
        Long chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        Map<String, CloseStatus> closes = listenCloses();
        StompSession flooder = stomp.connect(HOST_ID);
        awaitInboundIdle();
        RoomLock lock = lockRoom(chatRoomId);
        try {
            // 첫째가 방 잠금을 기다리며 처리 스레드를 잡고, 나머지가 그 연결의 줄에 쌓여 운영 기본 몫(64)을 넘긴다
            for (int i = 1; i <= 70; i++) {
                flooder.send("/pub/chats/" + chatRoomId + "/messages", new SendChatMessageRequest("폭주", "flood-" + i));
            }
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> closes.containsKey(String.valueOf(HOST_ID)));
            assertThat(closes.get(String.valueOf(HOST_ID)).getReason()).isEqualTo("INBOUND_OVERFLOW");
        } finally {
            lock.release();
        }
        awaitInboundIdle();
        // 몫 안에서 받은 64개(처리 중 1 + 줄 63)만 보낸 순서대로 저장된다
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> clientMessageIds(chatRoomId).size() == 64);
        Thread.sleep(300);
        assertThat(clientMessageIds(chatRoomId)).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, 64).mapToObj(i -> "flood-" + i).toList());

        assertNoNewSessionQueues();
    }

    @Test
    void 처리_대기_프레임을_남긴_채_웹소켓만_닫혀도_받은_프레임을_처리하고_처리_줄이_남지_않는다() throws Exception {
        Long chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        RawStomp raw = rawConnect(HOST_ID);
        awaitInboundIdle();
        RoomLock lock = lockRoom(chatRoomId);
        try {
            for (int i = 1; i <= 5; i++) {
                raw.send(chatRoomId, "abrupt-" + i);
            }
            // STOMP DISCONNECT 없이 웹소켓만 닫는다. 서버는 줄 뒤에 세션 종료 정리를 넣는다
            raw.session().close();
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> localChatConnections.connectedUserIds().isEmpty());
        } finally {
            lock.release();
        }
        awaitInboundIdle();
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> clientMessageIds(chatRoomId).size() == 5);

        assertNoNewSessionQueues();
    }

    /**
     * 처리 대기 SEND 9개(처리기 셋 = 작업 27개)를 남긴 채 웹소켓이 닫히면, 세션 종료 정리(작업 3개)가 한 줄 비우기 단위(30)의 끝에 놓인다.
     * 그래도 연결별 줄은 지워져야 한다.
     */
    @Test
    void 처리_대기_SEND_9개를_남긴_채_웹소켓이_닫혀도_처리_줄이_남지_않는다() throws Exception {
        Long chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        RawStomp raw = rawConnect(HOST_ID);
        awaitInboundIdle();
        RoomLock lock = lockRoom(chatRoomId);
        try {
            for (int i = 1; i <= 9; i++) {
                raw.send(chatRoomId, "boundary-" + i);
            }
            raw.session().close();
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> localChatConnections.connectedUserIds().isEmpty());
            // 세션 종료 정리까지 줄에 들어간 뒤 잠금을 푼다
            Thread.sleep(300);
        } finally {
            lock.release();
        }
        awaitInboundIdle();
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> clientMessageIds(chatRoomId).size() == 9);

        assertNoNewSessionQueues();
    }

    /**
     * 운영 기본값에서 한 연결이 SEND 50개를 연달아 보낸다(예: 재연결 직후 미확인 전송을 한꺼번에 다시 보냄). 몫(64) 안이므로 연결은
     * 닫히지 않고 50개 모두 보낸 순서대로 저장된다(닫혔는지와 저장 수를 로그로도 남긴다).
     */
    @Test
    void 운영_기본값에서_SEND_50개를_연달아_보내면_어떻게_되는지_기록한다() throws Exception {
        Long chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        Map<String, CloseStatus> closes = listenCloses();
        StompSession host = stomp.connect(HOST_ID);
        awaitInboundIdle();
        int written = 0;
        for (int i = 1; i <= 50; i++) {
            try {
                host.send("/pub/chats/" + chatRoomId + "/messages", new SendChatMessageRequest("연달아 " + i, "burst-" + i));
                written++;
            } catch (org.springframework.messaging.MessageDeliveryException | IllegalStateException closedByServer) {
                // 서버가 몫 초과로 먼저 닫으면 나머지는 쓰지 못한다. 닫힌 시점에 따라 STOMP 클라이언트가 전송 실패
                // (MessageDeliveryException) 또는 이미 닫힌 연결(IllegalStateException "Connection closed")로 알린다
                break;
            }
        }
        awaitInboundIdle();
        Thread.sleep(500);
        List<String> stored = clientMessageIds(chatRoomId);
        CloseStatus close = closes.get(String.valueOf(HOST_ID));
        System.out.println("BURST50 default cap: written=" + written + ", closed=" + close + ", stored=" + stored.size());
        assertThat(close).as("몫(64) 안의 50개는 닫지 않는다").isNull();
        assertThat(written).isEqualTo(50);
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> clientMessageIds(chatRoomId).size() == 50);
        stored = clientMessageIds(chatRoomId);
        // 저장된 것은 보낸 순서의 앞부분이다(순서가 바뀌지 않는다)
        assertThat(stored).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, stored.size()).mapToObj(i -> "burst-" + i).toList());
    }

    private Map<String, ?> queues() {
        @SuppressWarnings("unchecked")
        Map<String, ?> queues = (Map<String, ?>) ReflectionTestUtils.getField(chatInboundReceiveBudget, "queues");
        return queues;
    }

    private Map<String, CloseStatus> listenCloses() {
        Map<String, CloseStatus> closes = new ConcurrentHashMap<>();
        ApplicationListener<SessionDisconnectEvent> listener = event -> {
            if (event.getUser() != null) {
                closes.putIfAbsent(event.getUser().getName(), event.getCloseStatus());
            }
        };
        applicationContext.addApplicationListener(listener);
        listeners.add(listener);
        return closes;
    }

    /** STOMP 클라이언트 없이 웹소켓에 STOMP 프레임을 직접 쓴다. STOMP DISCONNECT 없이 웹소켓만 닫을 수 있다 */
    private RawStomp rawConnect(Long userId) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(HttpHeaders.COOKIE, E2ESession.login(restTestClient, userId, Role.INFLUENCER).cookieHeader());
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        WebSocketSession session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                frames.add(message.getPayload());
            }
        }, headers, URI.create("ws://localhost:" + port + "/api/v1/chats/ws")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        session.sendMessage(new TextMessage("CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\n\n\0"));
        String connected = frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(connected).startsWith("CONNECTED");
        return new RawStomp(session);
    }

    private record RawStomp(WebSocketSession session) {

        void send(Long chatRoomId, String clientMessageId) throws Exception {
            String body = "{\"content\":\"raw\",\"clientMessageId\":\"" + clientMessageId + "\"}";
            session.sendMessage(new TextMessage("SEND\ndestination:/pub/chats/" + chatRoomId
                    + "/messages\ncontent-type:application/json\n\n" + body + "\0"));
        }
    }

    private RoomLock lockRoom(Long chatRoomId) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactionTemplate
                .executeWithoutResult(status -> {
                    sql.single("select id from chats.chat_room where id = :id for update", Long.class,
                            Map.of("id", chatRoomId));
                    locked.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }));
        assertThat(locked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        return new RoomLock(release, holder);
    }

    private record RoomLock(CountDownLatch unlock, CompletableFuture<Void> holder) {

        void release() throws Exception {
            unlock.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
    }

    private void awaitInboundIdle() {
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(() -> chatInboundReceiveBudget.executor().getActiveCount() == 0
                        && chatInboundReceiveBudget.executor().getQueueSize() == 0
                        && chatInboundReceiveBudget.sessionsWithQueuedWork() == 0);
    }

    private List<String> clientMessageIds(Long chatRoomId) {
        return sql.list(
                "select client_message_id from chats.chat_message where chat_room_id = :roomId"
                        + " and client_message_id is not null order by room_sequence", String.class,
                Map.of("roomId", chatRoomId));
    }
}
