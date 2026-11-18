package com.hapbang.chat.in.websocket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.StompSubProtocolHandler;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

import com.hapbang.chat.app.ChatRoomTopicFacade;

/**
 * 연결이 열리고 닫힐 때의 순서. 닫힐 때는 Spring의 STOMP 처리기가 같은 스레드에서 {@link SessionDisconnectEvent}를 내고, 그 리스너가
 * 방 topic 구독을 정리한 뒤에 연결 목록에서 뺀다. 운영과 같은 STOMP 처리기({@link SubProtocolWebSocketHandler} +
 * {@link StompSubProtocolHandler})를 감싸 닫힘 경로마다 확인한다.
 */
class ChatWebSocketLifecycleDecoratorTest {

    private final ChatRoomTopicFacade topics = mock(ChatRoomTopicFacade.class);
    private final ChatWebSocketSessionRegistry registry = mock(ChatWebSocketSessionRegistry.class);
    private final ChatSessionDisconnectListener disconnectListener = new ChatSessionDisconnectListener(topics);
    private final List<String> threads = new CopyOnWriteArrayList<>();
    private final WebSocketHandler handler = new ChatWebSocketLifecycleDecorator(registry).decorate(stompHandler());
    private final WebSocketSession session = session("s-1");

    /** 서버가 닫은 연결(만료·로그아웃·채팅 불가·넘침), 첫 프레임이 없는 연결, 앱 종료, 정상 종료, 비정상 끊김 */
    static Stream<Arguments> closeStatuses() {
        return Stream.of(
                Arguments.of(CloseStatus.NORMAL),
                Arguments.of(ChatWebSocketSessionRegistry.TOKEN_EXPIRED),
                Arguments.of(ChatWebSocketSessionRegistry.LOGGED_OUT),
                Arguments.of(ChatWebSocketSessionRegistry.CHAT_NOT_ALLOWED),
                Arguments.of(ChatWebSocketSessionRegistry.OUTBOUND_OVERFLOW),
                Arguments.of(CloseStatus.SESSION_NOT_RELIABLE),
                Arguments.of(CloseStatus.GOING_AWAY),
                Arguments.of(new CloseStatus(1006)));
    }

    @Test
    void 열리면_연결_목록에_넣고_구독은_정리하지_않는다() throws Exception {
        handler.afterConnectionEstablished(session);

        verify(registry).register(session);
        verify(registry, never()).unregister(any());
        verifyNoInteractions(topics);
    }

    @ParameterizedTest
    @MethodSource("closeStatuses")
    void STOMP_CONNECT_뒤_닫히면_같은_스레드의_닫힘_이벤트로_구독을_정리한_뒤_연결_목록에서_뺀다(CloseStatus status)
            throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new TextMessage("CONNECT\naccept-version:1.2\nhost:chat\n\n\0"));

        handler.afterConnectionClosed(session, status);

        assertCleanedThenRemoved();
    }

    @ParameterizedTest
    @MethodSource("closeStatuses")
    void STOMP_CONNECT_전에_닫혀도_닫힘_이벤트로_구독을_정리한_뒤_연결_목록에서_뺀다(CloseStatus status) throws Exception {
        handler.afterConnectionEstablished(session);

        handler.afterConnectionClosed(session, status);

        assertCleanedThenRemoved();
    }

    @Test
    void 전송_오류는_목록을_건드리지_않고_뒤따르는_닫힘에서_구독을_정리한_뒤_연결_목록에서_뺀다() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.handleTransportError(session, new IOException("연결 끊김"));

        verifyNoInteractions(topics);
        verify(registry, never()).unregister(any());

        handler.afterConnectionClosed(session, CloseStatus.SERVER_ERROR);

        assertCleanedThenRemoved();
    }

    @Test
    void 다음_처리기가_열기에_실패하면_연결_목록에서_빼고_예외를_그대로_던진다() throws Exception {
        WebSocketHandler failing = mock(WebSocketHandler.class);
        IllegalStateException failure = new IllegalStateException("처리기 실패");
        doThrow(failure).when(failing).afterConnectionEstablished(session);
        WebSocketHandler decorated = new ChatWebSocketLifecycleDecorator(registry).decorate(failing);

        assertThatThrownBy(() -> decorated.afterConnectionEstablished(session)).isSameAs(failure);

        InOrder order = inOrder(registry);
        order.verify(registry).register(session);
        order.verify(registry).unregister(session);
    }

    @Test
    void 다음_처리기의_닫힘_처리가_실패해도_연결_목록에서_뺀다() throws Exception {
        WebSocketHandler failing = mock(WebSocketHandler.class);
        IllegalStateException failure = new IllegalStateException("닫힘 처리 실패");
        doThrow(failure).when(failing).afterConnectionClosed(session, CloseStatus.NORMAL);
        WebSocketHandler decorated = new ChatWebSocketLifecycleDecorator(registry).decorate(failing);

        assertThatThrownBy(() -> decorated.afterConnectionClosed(session, CloseStatus.NORMAL)).isSameAs(failure);

        verify(registry).unregister(session);
    }

    private void assertCleanedThenRemoved() {
        InOrder order = inOrder(topics, registry);
        order.verify(topics).sessionClosed("s-1");
        order.verify(registry).unregister(session);
        assertThat(threads).containsOnly(Thread.currentThread().getName()).hasSize(2);
    }

    /** 운영과 같은 STOMP 처리기. 닫힘 이벤트는 테스트 스레드에서 바로 리스너로 간다(Spring 기본 이벤트 전달과 같다) */
    private WebSocketHandler stompHandler() {
        doAnswer(invocation -> threads.add(Thread.currentThread().getName())).when(topics).sessionClosed(anyString());
        doAnswer(invocation -> threads.add(Thread.currentThread().getName())).when(registry).unregister(any());
        SubProtocolWebSocketHandler stomp = new SubProtocolWebSocketHandler(new ExecutorSubscribableChannel(),
                new ExecutorSubscribableChannel());
        StompSubProtocolHandler protocol = new StompSubProtocolHandler();
        protocol.setApplicationEventPublisher(event -> {
            if (event instanceof SessionDisconnectEvent disconnect) {
                disconnectListener.onDisconnect(disconnect);
            }
        });
        stomp.addProtocolHandler(protocol);
        stomp.setDefaultProtocolHandler(protocol);
        return stomp;
    }

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.getTextMessageSizeLimit()).thenReturn(64 * 1024);
        when(session.getAcceptedProtocol()).thenReturn(null);
        return session;
    }
}
