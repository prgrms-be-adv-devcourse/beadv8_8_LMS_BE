package com.hapbang.chat.in.websocket;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import com.hapbang.chat.app.dto.ChatConnectionTicket;

/**
 * 웹소켓 연결의 사용자를 연결 티켓으로 정한다(설계 문서 6장). 핸드셰이크 요청의 사용자(Gateway 사용자 헤더 등)는 쓰지 않는다.
 * 사용자는 {@code getName()} = 회원 ID, 권한 {@code ROLE_{역할}} 하나다. STOMP 프레임의 사용자({@code Principal})가 이 값이다.
 * 티켓을 쓰지 않은 연결은 사용자가 없고, CONNECT에서 거절된다.
 */
public class ChatHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        if (!(attributes.get(ChatConnectionTicketHandshakeInterceptor.CONNECTION_ATTRIBUTE)
                instanceof ChatConnectionTicket connection)) {
            return null;
        }
        return new PreAuthenticatedAuthenticationToken(String.valueOf(connection.userId()), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + connection.role().name())));
    }
}
