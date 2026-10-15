package com.hapbang.chat.in.websocket;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import lombok.extern.slf4j.Slf4j;

/**
 * 열린 채팅 웹소켓 연결 목록. 로그아웃·정지·강등 때 서버가 연결을 닫기 위해 둔다 (#242).
 * <p>
 * 세션 저장소가 JDBC라 HTTP 세션 삭제 이벤트가 없어서, Spring Session의 웹소켓 연동으로는 소켓이 닫히지 않는다.
 * 그래서 연결이 열리고 닫힐 때 직접 기록하고, 로그아웃 이벤트·회원 변경 이벤트를 받으면 해당 연결을 닫는다.
 * 같은 서버의 연결만 안다(단일 인스턴스).
 */
@Slf4j
@Component
public class ChatWebSocketSessionRegistry implements WebSocketHandlerDecoratorFactory {

    /** 로그아웃해서 닫음. 프론트는 재연결하지 않는다 */
    public static final CloseStatus LOGGED_OUT = CloseStatus.POLICY_VIOLATION.withReason("LOGGED_OUT");
    /** 정지·탈퇴·강등으로 채팅할 수 없게 되어 닫음. 프론트는 재연결하지 않는다 */
    public static final CloseStatus CHAT_NOT_ALLOWED = CloseStatus.POLICY_VIOLATION.withReason("CHAT_NOT_ALLOWED");

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                try {
                    super.afterConnectionEstablished(session);
                } catch (Exception exception) {
                    // Spring이 오류로 닫으면 afterConnectionClosed도 오지만, 남지 않도록 먼저 지운다
                    sessions.remove(session.getId());
                    throw exception;
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                sessions.remove(session.getId());
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
