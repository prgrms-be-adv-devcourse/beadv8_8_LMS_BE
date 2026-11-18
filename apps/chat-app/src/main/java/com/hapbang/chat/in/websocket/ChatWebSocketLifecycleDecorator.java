package com.hapbang.chat.in.websocket;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import com.hapbang.chat.app.ChatRoomTopicFacade;

/**
 * 채팅 웹소켓 연결이 열리고 닫히는 것을 연결 목록({@link ChatWebSocketSessionRegistry})과 방 topic 구독 목록에 알린다.
 * <ul>
 *     <li>열릴 때: 연결 목록에 넣고(핸드셰이크의 연결 티켓으로 회원 결합) 다음 처리기를 부른다. 다음 처리기가 실패하면 Spring이
 *     오류로 닫아 {@code afterConnectionClosed}도 오지만, 남지 않도록 먼저 지우고 예외를 그대로 던진다.</li>
 *     <li>닫힐 때: 방 topic 구독을 먼저 정리하고 연결 목록에서 뺀 뒤 다음 처리기를 부른다.</li>
 * </ul>
 * 모두 연결이 열리고 닫히는 스레드에서 차례로 한다. 연결 목록은 이 decorator를 모르고, 의존은 이쪽에서 연결 목록·Facade로 한 방향이다.
 * <p>
 * 방 topic 구독 정리는 프레임을 쓰는 송신기를 거쳐 메시지 브로커 설정에 닿고, 그 설정({@link ChatWebSocketConfig})이 이 decorator를
 * 쓴다. 생성 순환을 피해 Facade를 지연해서 얻는다({@link ChatStompInterceptor}와 같다).
 */
@Component
public class ChatWebSocketLifecycleDecorator implements WebSocketHandlerDecoratorFactory {

    private final ChatRoomTopicFacade chatRoomTopicFacade;
    private final ChatWebSocketSessionRegistry chatWebSocketSessionRegistry;

    public ChatWebSocketLifecycleDecorator(@Lazy ChatRoomTopicFacade chatRoomTopicFacade,
            ChatWebSocketSessionRegistry chatWebSocketSessionRegistry) {
        this.chatRoomTopicFacade = chatRoomTopicFacade;
        this.chatWebSocketSessionRegistry = chatWebSocketSessionRegistry;
    }

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                chatWebSocketSessionRegistry.register(session);
                try {
                    super.afterConnectionEstablished(session);
                } catch (Exception exception) {
                    // Spring이 오류로 닫으면 afterConnectionClosed도 오지만, 남지 않도록 먼저 지운다
                    closed(session);
                    throw exception;
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                closed(session);
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    /** 방 topic 구독을 정리하고 연결 목록에서 뺀다 */
    private void closed(WebSocketSession session) {
        chatRoomTopicFacade.sessionClosed(session.getId());
        chatWebSocketSessionRegistry.unregister(session);
    }
}
