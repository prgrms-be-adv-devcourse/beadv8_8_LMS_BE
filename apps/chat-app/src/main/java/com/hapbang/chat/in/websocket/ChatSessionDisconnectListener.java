package com.hapbang.chat.in.websocket;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.hapbang.chat.app.ChatRoomTopicFacade;

import lombok.RequiredArgsConstructor;

/**
 * 웹소켓 연결이 닫히면 그 연결의 방 topic 구독을 정리한다. Spring의 STOMP 처리기는 연결이 닫힐 때마다(정상 종료, 전송 오류 뒤 종료,
 * CONNECT 전 종료, 서버가 닫은 연결, 송신 상한 초과) 닫힘 처리 스레드에서 {@link SessionDisconnectEvent}를 바로 낸다. 이 리스너는
 * 비동기로 돌지 않으므로 {@link ChatWebSocketLifecycleDecorator}가 연결 목록에서 빼기 전에 정리가 끝난다.
 * <p>
 * 한 연결에 이벤트가 두 번 올 수 있다(송신 상한 초과는 닫기 전에 한 번, 닫힌 뒤에 한 번). 정리는 여러 번 해도 같다.
 */
@Component
@RequiredArgsConstructor
public class ChatSessionDisconnectListener {

    private final ChatRoomTopicFacade chatRoomTopicFacade;

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        chatRoomTopicFacade.sessionClosed(event.getSessionId());
    }
}
