package com.hapbang.journey.support;

import static org.awaitility.Awaitility.await;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * 여정 테스트의 STOMP 클라이언트 (chat 모듈 ChatStompE2ETest의 연결·구독·종료 감지 헬퍼를 옮김).
 * 핸드셰이크는 실제 JDBC 세션의 {@code SESSION} 쿠키({@link JourneySessions})로 한다.
 * 서버가 닫은 연결은 {@link SessionDisconnectEvent}로 회원별로 모은다. 테스트가 끝나면 {@link #closeAll}로 연결과 리스너를 정리한다.
 */
public class JourneyStomp {

    public static final long TIMEOUT_SECONDS = 5;

    private final WebSocketStompClient client;
    private final String wsUrl;
    private final ConfigurableApplicationContext applicationContext;
    private final SimpUserRegistry userRegistry;
    private final List<StompSession> sessions = new ArrayList<>();
    private final List<ApplicationListener<SessionDisconnectEvent>> listeners = new ArrayList<>();

    public JourneyStomp(int port, ConfigurableApplicationContext applicationContext) {
        this.userRegistry = applicationContext.getBean(SimpUserRegistry.class);
        this.client = new WebSocketStompClient(new StandardWebSocketClient());
        this.client.setMessageConverter(new JacksonJsonMessageConverter());
        this.wsUrl = "ws://localhost:" + port + "/api/v1/chats/ws";
        this.applicationContext = applicationContext;
    }

    /** 이 세션(로그인)의 쿠키로 연결한다. 핸드셰이크·CONNECT가 거절되면 예외 */
    public StompSession connect(String sessionId) throws Exception {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add(HttpHeaders.COOKIE, JourneySessions.cookie(sessionId).getName() + "="
                + JourneySessions.cookie(sessionId).getValue());
        CompletableFuture<StompSession> connecting = client.connectAsync(wsUrl, handshakeHeaders, new StompHeaders(),
                new StompSessionHandlerAdapter() {
                });
        try {
            StompSession session = connecting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            sessions.add(session);
            return session;
        } catch (TimeoutException timeout) {
            // 시간 초과 뒤 늦게 맺어진 연결은 바로 닫는다 (closeAll이 모르는 연결이 남지 않게)
            connecting.whenComplete((late, failure) -> {
                if (late != null) {
                    late.disconnect();
                }
            });
            throw timeout;
        }
    }

    public <T> BlockingQueue<T> subscribe(StompSession session, String destination, Class<T> type) {
        BlockingQueue<T> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return type;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add(type.cast(payload));
            }
        });
        return queue;
    }

    /**
     * 서버 구독 등록부에 이 회원들의 구독이 {@code expected}개 이상 등록될 때까지 기다린다. 내장 브로커는 SUBSCRIBE 처리 완료(RECEIPT)를
     * 보내지 않고, 등록 전에 보낸 메시지는 보관하지 않으므로 고정 지연 대신 실제 등록을 확인한다
     */
    public void awaitSubscriptions(int expected, Long... userIds) {
        Set<String> names = Arrays.stream(userIds).map(String::valueOf).collect(Collectors.toSet());
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> userRegistry.findSubscriptions(
                subscription -> names.contains(subscription.getSession().getUser().getName())).size() >= expected);
    }

    /** 이 회원의 연결이 서버에서 닫힐 때의 상태를 모은다. 동작 전에 불러 둔다 */
    public BlockingQueue<CloseStatus> disconnectsOf(Long userId) {
        BlockingQueue<CloseStatus> disconnects = new LinkedBlockingQueue<>();
        ApplicationListener<SessionDisconnectEvent> listener = event -> {
            if (event.getUser() != null && String.valueOf(userId).equals(event.getUser().getName())) {
                disconnects.add(event.getCloseStatus());
            }
        };
        applicationContext.addApplicationListener(listener);
        listeners.add(listener);
        return disconnects;
    }

    /** 연결·리스너·클라이언트를 정리한다. 한 연결의 종료가 실패해도 나머지 정리는 계속한다 */
    public void closeAll() {
        try {
            for (StompSession session : sessions) {
                try {
                    if (session.isConnected()) {
                        session.disconnect();
                    }
                } catch (RuntimeException ignored) {
                    // 서버가 먼저 닫은 연결 등. 나머지 연결 정리를 계속한다
                }
            }
        } finally {
            sessions.clear();
            listeners.forEach(applicationContext::removeApplicationListener);
            listeners.clear();
            client.stop();
        }
    }
}
