package com.hapbang.auxiliary.notification.in.websocket;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import com.hapbang.auxiliary.notification.app.ConditionalOnNotificationRealtime;
import com.hapbang.auxiliary.notification.app.NotificationConnectionFacade;
import com.hapbang.auxiliary.notification.app.dto.ConnectionTicket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 핸드셰이크에서 연결 티켓을 쓴다(ND-028·ND-060). 브라우저는 업그레이드 요청에 Authorization을 싣지 못하므로 REST로 받은 일회용 티켓을
 * {@code ticket} 쿼리로 보낸다. 이 경로는 공통 보안 설정에서 열려 있으므로(ND-060) 인증은 여기서만 한다.
 * <ul>
 *     <li>허용 출처 검사가 먼저 돈다({@link NotificationWebSocketConfig}).</li>
 *     <li>티켓이 없거나, 모양이 틀리거나, 만료됐거나, 이미 썼으면 업그레이드 전에 401로 거절한다. 핸드셰이크 요청의 사용자 헤더는 보지
 *     않는다.</li>
 *     <li>성공하면 티켓 내용을 {@link #CONNECTION_ATTRIBUTE}에 넣는다. 연결 사용자({@link NotificationHandshakeHandler})와 연결 만료
 *     ({@link NotificationWebSocketSessions})가 이 값을 쓴다. 티켓 원문은 로그에 남기지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnNotificationRealtime
@RequiredArgsConstructor
public class NotificationTicketHandshakeInterceptor implements HandshakeInterceptor {

    public static final String TICKET_PARAMETER = "ticket";
    public static final String CONNECTION_ATTRIBUTE = NotificationTicketHandshakeInterceptor.class.getName() + ".TICKET";

    private final NotificationConnectionFacade facade;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        String ticket = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams()
                .getFirst(TICKET_PARAMETER);
        ConnectionTicket connection = facade.consumeTicket(ticket).orElse(null);
        if (connection == null) {
            log.info("알림 연결 티켓이 없거나 쓸 수 없어 핸드셰이크를 거절함 hasTicket={}", ticket != null);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(CONNECTION_ATTRIBUTE, connection);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
            Exception exception) {
    }
}
