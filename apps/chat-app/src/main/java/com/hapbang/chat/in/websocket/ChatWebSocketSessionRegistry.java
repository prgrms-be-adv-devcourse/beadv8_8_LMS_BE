package com.hapbang.chat.in.websocket;

import java.io.IOException;
import java.security.Principal;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import com.hapbang.chat.app.ChatPresence;
import com.hapbang.chat.app.ChatRoomTopicFacade;
import com.hapbang.chat.app.LocalChatConnections;

import lombok.extern.slf4j.Slf4j;

/**
 * 열린 채팅 웹소켓 연결 목록. 로그아웃·정지·강등 때 서버가 연결을 닫기 위해 둔다 (#242).
 * <p>
 * 세션 저장소가 JDBC라 HTTP 세션 삭제 이벤트가 없어서, Spring Session의 웹소켓 연동으로는 소켓이 닫히지 않는다.
 * 그래서 연결이 열리고 닫힐 때 직접 기록하고, 로그아웃 이벤트·회원 변경 이벤트를 받으면 해당 연결을 닫는다.
 * 같은 서버의 연결만 안다(단일 인스턴스).
 * <p>
 * 실시간 전달·새 메시지 알림이 회원의 로컬 연결을 고르므로 회원별 연결 목록도 함께 둔다. 연결이 열리고 닫힐 때 Redis 접속
 * 정보를 기록하고, 닫힐 때 그 연결의 방 topic 구독을 지운다.
 */
@Slf4j
@Component
public class ChatWebSocketSessionRegistry implements WebSocketHandlerDecoratorFactory, LocalChatConnections {

    /** 로그아웃해서 닫음. 프론트는 재연결하지 않는다 */
    public static final CloseStatus LOGGED_OUT = CloseStatus.POLICY_VIOLATION.withReason("LOGGED_OUT");
    /** 정지·탈퇴·강등으로 채팅할 수 없게 되어 닫음. 프론트는 재연결하지 않는다 */
    public static final CloseStatus CHAT_NOT_ALLOWED = CloseStatus.POLICY_VIOLATION.withReason("CHAT_NOT_ALLOWED");

    /**
     * 앱 전체 송신 대기열(clientOutboundChannel)이 넘쳐 이 연결로 가는 프레임을 버려서 닫음.
     * 정책 종료가 아니므로 프론트는 재연결하고 재동기화한다(resync-frame.md의 "그 밖의 종료")
     */
    public static final CloseStatus OUTBOUND_OVERFLOW = CloseStatus.SESSION_NOT_RELIABLE.withReason("OUTBOUND_OVERFLOW");
    /**
     * 앱 전체 수신 대기열(clientInboundChannel)이 넘쳐 이 연결이 보낸 프레임을 버려서 닫음.
     * 정책 종료가 아니므로 프론트는 재연결하고 재동기화한다. 버린 전송은 결과 미확인으로 같은 전송 ID로 다시 보낸다
     */
    public static final CloseStatus INBOUND_OVERFLOW = CloseStatus.SESSION_NOT_RELIABLE.withReason("INBOUND_OVERFLOW");

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    /** 실시간 전달을 끈 실행에는 없다(Redis 접속 정보) */
    private final ObjectProvider<ChatPresence> chatPresence;
    /** 방 topic 구독 목록 정리. 이 목록이 연결 목록을 쓰므로 생성 순환을 피해 지연해서 얻는다 */
    private final ObjectProvider<ChatRoomTopicFacade> chatRoomTopicFacade;

    public ChatWebSocketSessionRegistry(ObjectProvider<ChatPresence> chatPresence,
            ObjectProvider<ChatRoomTopicFacade> chatRoomTopicFacade) {
        this.chatPresence = chatPresence;
        this.chatRoomTopicFacade = chatRoomTopicFacade;
    }
    /** 회원 ID → 그 회원의 연결 ID. 회원 ID로 읽을 수 없는 연결은 넣지 않는다 */
    private final Map<Long, Set<String>> sessionIdsByUserId = new ConcurrentHashMap<>();

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                register(session);
                try {
                    super.afterConnectionEstablished(session);
                } catch (Exception exception) {
                    // Spring이 오류로 닫으면 afterConnectionClosed도 오지만, 남지 않도록 먼저 지운다
                    unregister(session);
                    throw exception;
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                unregister(session);
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    /** 이 HTTP 세션(로그인)으로 맺은 연결만 닫는다. 같은 회원의 다른 기기 연결은 유지한다 */
    public int closeByHttpSessionId(String httpSessionId, CloseStatus status) {
        return closeAll(session -> Objects.equals(httpSessionId,
                session.getAttributes().get(HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME)), status);
    }

    /** 이 회원의 모든 연결을 닫는다 */
    public int closeByUserId(Long userId, CloseStatus status) {
        String name = String.valueOf(userId);
        return closeAll(session -> {
            Principal principal = session.getPrincipal();
            return principal != null && name.equals(principal.getName());
        }, status);
    }

    /**
     * 이 연결 하나를 닫는다. STOMP 세션 ID는 WebSocket 세션 ID와 같다.
     *
     * @return 닫기를 요청했으면 true. 이미 목록에 없으면 false
     */
    public boolean closeSession(String sessionId, CloseStatus status) {
        WebSocketSession session = sessions.get(sessionId);
        return session != null && closeAll(session::equals, status) > 0;
    }

    @Override
    public Set<Long> connectedUserIds() {
        return Set.copyOf(sessionIdsByUserId.keySet());
    }

    @Override
    public Set<Long> connectedAmong(Collection<Long> userIds) {
        Set<Long> connected = new HashSet<>();
        for (Long userId : userIds) {
            if (userId != null && sessionIdsByUserId.containsKey(userId)) {
                connected.add(userId);
            }
        }
        return connected;
    }

    @Override
    public Set<String> sessionIdsOf(Long userId) {
        Set<String> ids = userId == null ? null : sessionIdsByUserId.get(userId);
        return ids == null ? Set.of() : Set.copyOf(ids);
    }

    @Override
    public boolean isConnected(String sessionId) {
        WebSocketSession session = sessionId == null ? null : sessions.get(sessionId);
        return session != null && session.isOpen();
    }

    @Override
    public int closeNotAllowed(Long userId) {
        return closeByUserId(userId, CHAT_NOT_ALLOWED);
    }

    /**
     * 연결을 목록에 넣는다. 회원 연결은 그 회원 키의 원자 구간({@code compute}) 안에서 두 목록을 함께 바꾸므로,
     * 같은 회원의 {@link #unregister}와 섞여 한쪽 목록에만 남지 않는다. 그 사이 이미 닫힌 연결은 넣지 않는다
     * (닫힘 처리가 먼저 끝났으면 다시 지울 기회가 없다).
     */
    private void register(WebSocketSession session) {
        Long userId = userIdOf(session);
        if (userId == null) {
            sessions.put(session.getId(), session);
            if (!session.isOpen()) {
                sessions.remove(session.getId());
            }
            return;
        }
        sessionIdsByUserId.compute(userId, (key, ids) -> {
            if (!session.isOpen()) {
                return ids;
            }
            Set<String> next = ids == null ? ConcurrentHashMap.newKeySet() : ids;
            sessions.put(session.getId(), session);
            next.add(session.getId());
            return next;
        });
        ChatPresence presence = chatPresence.getIfAvailable();
        if (presence != null) {
            try {
                presence.connected(userId, session.getId());
            } catch (RuntimeException exception) {
                // 다음 하트비트가 다시 넣는다. 그 사이 새 메시지 알림을 놓치면 FE 주기 대조가 메운다
                log.warn("접속 정보 기록 실패 userId={}, sessionId={}", userId, session.getId(), exception);
            }
        }
    }

    private void unregister(WebSocketSession session) {
        ChatRoomTopicFacade topics = chatRoomTopicFacade.getIfAvailable();
        if (topics != null) {
            topics.sessionClosed(session.getId());
        }
        Long userId = userIdOf(session);
        if (userId == null) {
            sessions.remove(session.getId());
            return;
        }
        unregisterUserSession(userId, session);
        ChatPresence presence = chatPresence.getIfAvailable();
        if (presence != null) {
            try {
                presence.disconnected(userId, session.getId());
            } catch (RuntimeException exception) {
                // 그 연결의 접속 정보는 만료로 사라진다
                log.warn("접속 정보 삭제 실패 userId={}, sessionId={}", userId, session.getId(), exception);
            }
        }
    }

    private void unregisterUserSession(Long userId, WebSocketSession session) {
        sessionIdsByUserId.compute(userId, (key, ids) -> {
            sessions.remove(session.getId());
            if (ids == null) {
                return null;
            }
            ids.remove(session.getId());
            return ids.isEmpty() ? null : ids;
        });
    }

    /** 핸드셰이크 로그인 사용자의 회원 ID. 없거나 숫자가 아니면 null(STOMP 연결 검사에서 거절된다) */
    private static Long userIdOf(WebSocketSession session) {
        Principal principal = session.getPrincipal();
        if (principal == null) {
            return null;
        }
        try {
            return Long.valueOf(principal.getName());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private int closeAll(Predicate<WebSocketSession> target, CloseStatus status) {
        int closed = 0;
        for (WebSocketSession session : sessions.values()) {
            if (!target.test(session)) {
                continue;
            }
            try {
                session.close(status);
                closed++;
            } catch (IOException | IllegalStateException exception) {
                // 이미 닫히는 중이거나 전송과 겹친 경우. 닫힘 처리는 afterConnectionClosed가 한다
                log.warn("채팅 웹소켓 연결 닫기 실패 sessionId={}", session.getId(), exception);
            }
        }
        return closed;
    }
}
