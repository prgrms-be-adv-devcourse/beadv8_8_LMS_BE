package com.hapbang.chat.in.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

/**
 * 채팅 웹소켓 연결이 열리고 닫히는 것을 연결 목록({@link ChatWebSocketSessionRegistry})에 알린다. 이 decorator는 Spring의 STOMP
 * 처리기({@code SubProtocolWebSocketHandler})를 감싼다.
 * <ul>
 *     <li>열릴 때: 연결 목록에 넣고(핸드셰이크의 연결 티켓으로 회원 결합) 다음 처리기를 부른다. 다음 처리기가 실패하면 연결 목록에서
 *     바로 빼고 예외를 그대로 던진다. 연결이 열리는 중이라 아직 받은 프레임이 없으므로 방 topic 구독이 없다. 혹시 구독 허가가 겹쳐도
 *     구독 허가는 넣은 뒤 연결 목록을 다시 보고 빠진 연결의 구독을 지운다({@code DefaultChatRoomTopicService}). 던진 예외로 Spring이
 *     연결을 닫으면 아래 닫힘 처리도 한 번 더 돈다(둘 다 여러 번 불러도 같다).</li>
 *     <li>닫힐 때: 다음 처리기(STOMP 처리기)를 먼저 부른다. STOMP 처리기는 같은 스레드에서 {@code SessionDisconnectEvent}를 내고,
 *     그 이벤트를 받은 {@link ChatSessionDisconnectListener}가 방 topic 구독을 정리한다. 그 뒤에 연결 목록에서 뺀다. 그래서 구독
 *     정리가 언제나 연결 제거보다 먼저다. 다음 처리기가 실패해도 연결 목록에서는 뺀다.</li>
 * </ul>
 * 연결 목록은 이 decorator를 모르고, 의존은 이쪽에서 연결 목록으로 한 방향이다.
 */
@Component
public class ChatWebSocketLifecycleDecorator implements WebSocketHandlerDecoratorFactory {

    private final ChatWebSocketSessionRegistry chatWebSocketSessionRegistry;

    public ChatWebSocketLifecycleDecorator(ChatWebSocketSessionRegistry chatWebSocketSessionRegistry) {
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
                    chatWebSocketSessionRegistry.unregister(session);
                    throw exception;
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                try {
                    // STOMP 처리기가 SessionDisconnectEvent를 같은 스레드에서 내고, 그 리스너가 방 topic 구독을 정리한다
                    super.afterConnectionClosed(session, closeStatus);
                } finally {
                    chatWebSocketSessionRegistry.unregister(session);
                }
            }
        };
    }
}
