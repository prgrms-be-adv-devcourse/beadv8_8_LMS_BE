package com.hapbang.chat.in;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import com.hapbang.chat.app.ChatConnectionTicketFacade;
import com.hapbang.chat.in.dto.ChatReauthFrame;
import com.hapbang.chat.in.websocket.ChatWebSocketConfig;

import lombok.RequiredArgsConstructor;

/**
 * 열린 웹소켓 연결의 재인증(설계 문서 6장 "재인증"). 연결 만료가 다가오면 프론트가 새 연결 티켓을 받아
 * {@code SEND /pub/chats/reauth}(헤더 {@code ticket}, 본문 없음)로 보낸다. 연결을 끊지 않고 그 연결의 만료만 바꾼다.
 * <ul>
 *     <li>회원을 결합한 연결에서만 여기 온다(CONNECT 전·만료가 지난 연결의 프레임은 {@code ChatStompAuthInterceptor}가 막는다).</li>
 *     <li>결과는 보낸 연결 하나에만 {@code /user/queue/chats/connection}으로 보낸다. 같은 회원의 다른 연결에는 보내지 않는다.</li>
 *     <li>실패해도 연결을 닫지 않는다. 연결은 기존 만료에 {@code 4401 TOKEN_EXPIRED}로 닫힌다.</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
public class ChatConnectionStompController {

    /** 재인증 프레임에 새 연결 티켓을 싣는 헤더 */
    public static final String TICKET_HEADER = "ticket";

    private final ChatConnectionTicketFacade chatConnectionTicketFacade;

    @MessageMapping(ChatWebSocketConfig.REAUTH_DESTINATION)
    @SendToUser(destinations = ChatWebSocketConfig.CONNECTION_QUEUE, broadcast = false)
    public ChatReauthFrame reauthenticate(SimpMessageHeaderAccessor headers) {
        return ChatReauthFrame.of(chatConnectionTicketFacade.reauthenticate(headers.getSessionId(),
                headers.getFirstNativeHeader(TICKET_HEADER)));
    }
}
