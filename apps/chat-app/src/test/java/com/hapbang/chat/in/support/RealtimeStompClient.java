package com.hapbang.chat.in.support;

import static org.awaitility.Awaitility.*;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpHeaders;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.hapbang.chat.app.LocalChatConnections;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.shared.member.domain.Role;

/**
 * 실시간 전달 E2E용 STOMP 클라이언트. 프레임 헤더(participation-version 등)까지 받아 확인한다.
 * <p>
 * {@link WebSocketStompClient#stop()}은 열린 STOMP 세션을 닫지 않는다. 그래서 만든 세션을 모두 기억했다가
 * {@link #close()}에서 끊고, 서버의 로컬 연결 목록이 빌 때까지 기다린다. 테스트마다 {@code @AfterEach}에서 부른다.
 * 남은 연결이 다음 테스트의 연결 종료·재동기화 검증에 섞이지 않게 하기 위해서다.
 */
public class RealtimeStompClient {

    public static final long TIMEOUT_SECONDS = 5;

    private final RestTestClient client;
    private final int port;
    private final LocalChatConnections connections;
    private final WebSocketStompClient stompClient;
    private final List<StompSession> sessions = new CopyOnWriteArrayList<>();

    public RealtimeStompClient(RestTestClient client, int port, LocalChatConnections connections) {
        this.client = client;
        this.port = port;
        this.connections = connections;
        this.stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        this.stompClient.setMessageConverter(new JacksonJsonMessageConverter());
    }

    public StompSession connect(Long userId) throws Exception {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add(HttpHeaders.COOKIE, E2ESession.login(client, userId, Role.INFLUENCER).cookieHeader());
        StompSession session = stompClient.connectAsync("ws://localhost:" + port + "/api/v1/chats/ws",
                handshakeHeaders, new StompHeaders(), new StompSessionHandlerAdapter() {
                }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    public <T> BlockingQueue<Frame<T>> subscribe(StompSession session, String destination, Class<T> type) {
        BlockingQueue<Frame<T>> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return type;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add(new Frame<>(headers, type.cast(payload)));
            }
        });
        return queue;
    }

    /**
     * 방 topic({@code /topic/chats/rooms/{roomId}})을 {@code participation-version} 헤더로 구독한다. 같은 구독으로 메시지와
     * 거절·회수 프레임이 오므로 payload는 Map으로 받고 {@link Frame#frameType()}으로 가른다.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public RoomSubscription subscribeRoom(StompSession session, Long roomId, int participationVersion) {
        BlockingQueue<Frame<Map>> queue = new LinkedBlockingQueue<>();
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/topic/chats/rooms/" + roomId);
        headers.add("participation-version", String.valueOf(participationVersion));
        StompSession.Subscription subscription = session.subscribe(headers, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders frameHeaders) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders frameHeaders, Object payload) {
                queue.add(new Frame<>(frameHeaders, (Map) payload));
            }
        });
        return new RoomSubscription(subscription, queue);
    }

    /** 방 topic을 구독하고 메시지 프레임만 {@code ChatMessageResponse}로 모은다(거절·회수 프레임은 버린다) */
    public BlockingQueue<Frame<ChatMessageResponse>> subscribeRoomMessages(StompSession session, Long roomId,
            int participationVersion) {
        BlockingQueue<Frame<ChatMessageResponse>> queue = new LinkedBlockingQueue<>();
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/topic/chats/rooms/" + roomId);
        headers.add("participation-version", String.valueOf(participationVersion));
        session.subscribe(headers, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders frameHeaders) {
                return ChatMessageResponse.class;
            }

            @Override
            public void handleFrame(StompHeaders frameHeaders, Object payload) {
                if (frameHeaders.getFirst("chat-frame-type") == null) {
                    queue.add(new Frame<>(frameHeaders, (ChatMessageResponse) payload));
                }
            }
        });
        return queue;
    }

    /** 방 topic 구독과 받은 프레임 */
    @SuppressWarnings("rawtypes")
    public record RoomSubscription(StompSession.Subscription subscription, BlockingQueue<Frame<Map>> frames) {
    }

    /** 내장 브로커는 SUBSCRIBE 처리 완료(RECEIPT)를 보내지 않으므로 구독이 등록될 때까지 잠깐 기다린다 */
    public static void waitForSubscriptions() throws InterruptedException {
        Thread.sleep(300);
    }

    /** 이 클라이언트로 연 세션을 모두 끊고, 서버의 로컬 연결 목록이 빌 때까지 기다린다 */
    public void close() {
        for (StompSession session : sessions) {
            if (!session.isConnected()) {
                continue;
            }
            try {
                session.disconnect();
            } catch (RuntimeException alreadyClosing) {
                // 서버가 먼저 닫는 중이면 끊기 프레임을 보내지 못할 수 있다. 서버 목록이 비는지로 확인한다
            }
        }
        sessions.clear();
        stompClient.stop();
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> connections.connectedUserIds().isEmpty());
    }

    /** 받은 프레임의 헤더와 payload */
    public record Frame<T>(StompHeaders headers, T payload) {

        public String participationVersion() {
            return headers.getFirst("participation-version");
        }

        /** 방 topic 프레임의 종류. 헤더가 없으면 메시지다 */
        public String frameType() {
            String type = headers.getFirst("chat-frame-type");
            return type == null ? "MESSAGE" : type;
        }
    }
}
