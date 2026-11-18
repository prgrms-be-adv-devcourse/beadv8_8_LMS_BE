package com.hapbang.chat.in.websocket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import com.hapbang.chat.app.ChatRoomTopicFacade;

/**
 * 연결이 열리고 닫힐 때 연결 목록·방 topic 구독 목록에 알리는 순서. 닫힐 때는 방 topic 구독을 먼저 정리하고 연결 목록에서 뺀 뒤 다음
 * 처리기를 부르며, 모두 연결 처리 스레드에서 한다.
 */
class ChatWebSocketLifecycleDecoratorTest {

    private final ChatRoomTopicFacade topics = mock(ChatRoomTopicFacade.class);
    private final ChatWebSocketSessionRegistry registry = mock(ChatWebSocketSessionRegistry.class);
    private final WebSocketHandler delegate = mock(WebSocketHandler.class);
    private final WebSocketHandler handler = new ChatWebSocketLifecycleDecorator(topics, registry).decorate(delegate);
    private final WebSocketSession session = session("s-1");

    @Test
    void 열리면_연결_목록에_넣은_뒤_다음_처리기를_부른다() throws Exception {
        handler.afterConnectionEstablished(session);

        InOrder order = inOrder(registry, delegate);
        order.verify(registry).register(session);
        order.verify(delegate).afterConnectionEstablished(session);
        verifyNoInteractions(topics);
        verify(registry, never()).unregister(any());
    }

    @Test
    void 닫히면_같은_스레드에서_방_topic_구독을_정리하고_연결_목록에서_뺀_뒤_다음_처리기를_부른다() throws Exception {
        List<String> threads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> threads.add(Thread.currentThread().getName())).when(topics).sessionClosed("s-1");
        doAnswer(invocation -> threads.add(Thread.currentThread().getName())).when(registry).unregister(session);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        InOrder order = inOrder(topics, registry, delegate);
        order.verify(topics).sessionClosed("s-1");
        order.verify(registry).unregister(session);
        order.verify(delegate).afterConnectionClosed(session, CloseStatus.NORMAL);
        assertThat(threads).containsOnly(Thread.currentThread().getName()).hasSize(2);
    }

    @Test
    void 다음_처리기가_열기에_실패하면_먼저_정리하고_예외를_그대로_던진다() throws Exception {
        IllegalStateException failure = new IllegalStateException("처리기 실패");
        doThrow(failure).when(delegate).afterConnectionEstablished(session);

        assertThatThrownBy(() -> handler.afterConnectionEstablished(session)).isSameAs(failure);

        InOrder order = inOrder(registry, topics);
        order.verify(registry).register(session);
        order.verify(topics).sessionClosed("s-1");
        order.verify(registry).unregister(session);
    }

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
