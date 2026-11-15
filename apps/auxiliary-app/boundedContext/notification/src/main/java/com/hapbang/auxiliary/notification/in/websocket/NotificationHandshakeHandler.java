package com.hapbang.auxiliary.notification.in.websocket;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import com.hapbang.auxiliary.notification.app.dto.ConnectionTicket;

/**
 * 웹소켓 연결의 사용자를 연결 티켓으로 정한다. 핸드셰이크 요청의 사용자(헤더)는 쓰지 않는다. 사용자 이름은 회원 ID이고, STOMP 사용자 목적지
 * ({@code /user/queue/notifications})가 이 이름으로 그 회원의 연결만 고른다.
 */
public class NotificationHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        if (!(attributes.get(NotificationTicketHandshakeInterceptor.CONNECTION_ATTRIBUTE)
                instanceof ConnectionTicket connection)) {
            return null;
        }
        return new PreAuthenticatedAuthenticationToken(String.valueOf(connection.userId()), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + connection.role().name())));
    }
}
