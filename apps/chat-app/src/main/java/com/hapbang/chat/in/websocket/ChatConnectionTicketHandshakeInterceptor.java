package com.hapbang.chat.in.websocket;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import com.hapbang.chat.app.ChatConnectionTicketFacade;
import com.hapbang.chat.app.dto.ChatConnectionTicket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 웹소켓 핸드셰이크에서 연결 티켓을 쓴다(설계 문서 6장). 브라우저는 업그레이드 요청에 Authorization을 싣지 못하므로, REST로 받은
 * 일회용 티켓을 연결 주소의 {@code ticket} 쿼리로 보낸다.
 * <ul>
 *     <li>허용 출처 검사가 먼저 돈다({@link ChatWebSocketConfig}가 출처 검사 인터셉터를 이 인터셉터 앞에 둔다). 출처가 거절된 요청은
 *     티켓을 쓰지 않는다.</li>
 *     <li>웹소켓 업그레이드 요청이 아니면 티켓을 쓰지 않고 넘긴다. 업그레이드 검사는 핸드셰이크 처리기가 한다(400).</li>
 *     <li>티켓이 없거나, 모양이 틀리거나, 만료됐거나, 이미 썼으면 업그레이드 전에 401로 거절한다. 브라우저에는 연결 실패(1006)로
 *     보인다.</li>
 *     <li>성공하면 티켓의 회원·역할·발급 시각·연결 만료를 핸드셰이크 속성({@link #CONNECTION_ATTRIBUTE})에 넣는다.
 *     {@link ChatHandshakeHandler}가 이 값으로 연결 사용자를 정하고, {@link ChatWebSocketSessionRegistry}가 연결이 열릴 때 회원·만료를
 *     결합한다.</li>
 *     <li>티켓 원문은 로그에 남기지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatConnectionTicketHandshakeInterceptor implements HandshakeInterceptor {

    /** 연결 주소의 티켓 쿼리 이름 */
    public static final String TICKET_PARAMETER = "ticket";
    /** 핸드셰이크·웹소켓 세션 속성: 쓴 티켓의 내용({@link ChatConnectionTicket}) */
    public static final String CONNECTION_ATTRIBUTE = ChatConnectionTicketHandshakeInterceptor.class.getName() + ".TICKET";

    private final ChatConnectionTicketFacade chatConnectionTicketFacade;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        if (!"websocket".equalsIgnoreCase(request.getHeaders().getUpgrade())) {
            return true;
        }
        String ticket = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams()
                .getFirst(TICKET_PARAMETER);
        ChatConnectionTicket connection = chatConnectionTicketFacade.consumeTicket(ticket).orElse(null);
        if (connection == null) {
            log.info("웹소켓 연결 티켓이 없거나 쓸 수 없어 핸드셰이크를 거절함 hasTicket={}", ticket != null);
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
