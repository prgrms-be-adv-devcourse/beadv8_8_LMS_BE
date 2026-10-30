package com.hapbang.chat.in.websocket;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

/**
 * 실시간 전달·재검사가 쓰는 회원별 로컬 연결 목록({@link com.hapbang.chat.app.LocalChatConnections})을 검증한다.
 */
class ChatWebSocketSessionRegistryTest {

    private final ChatWebSocketSessionRegistry registry = new ChatWebSocketSessionRegistry(absent(), absent());
    private final WebSocketHandler delegate = mock(WebSocketHandler.class);
    private final WebSocketHandler handler = registry.decorate(delegate);

    @Test
    void 같은_회원의_연결이_하나라도_남아_있으면_로컬_연결이_있는_회원으로_본다() throws Exception {
        WebSocketSession hostDevice1 = session("s-1", String.valueOf(HOST_ID));
        WebSocketSession hostDevice2 = session("s-2", String.valueOf(HOST_ID));
        WebSocketSession guest = session("s-3", String.valueOf(GUEST_ID));
        handler.afterConnectionEstablished(hostDevice1);
        handler.afterConnectionEstablished(hostDevice2);
        handler.afterConnectionEstablished(guest);

        assertThat(registry.connectedUserIds()).containsExactlyInAnyOrder(HOST_ID, GUEST_ID);
        assertThat(registry.connectedAmong(List.of(HOST_ID, ADMIN_ID))).containsExactly(HOST_ID);

        handler.afterConnectionClosed(hostDevice1, CloseStatus.NORMAL);
        assertThat(registry.connectedAmong(List.of(HOST_ID))).containsExactly(HOST_ID);

        handler.afterConnectionClosed(hostDevice2, CloseStatus.NORMAL);
        assertThat(registry.connectedAmong(List.of(HOST_ID, GUEST_ID))).containsExactly(GUEST_ID);
        assertThat(registry.connectedUserIds()).containsExactly(GUEST_ID);
    }

    @Test
    void 채팅_불가_닫기는_그_회원의_모든_로컬_연결을_CHAT_NOT_ALLOWED로_닫는다() throws Exception {
        WebSocketSession hostDevice1 = session("s-1", String.valueOf(HOST_ID));
        WebSocketSession hostDevice2 = session("s-2", String.valueOf(HOST_ID));
        WebSocketSession guest = session("s-3", String.valueOf(GUEST_ID));
        handler.afterConnectionEstablished(hostDevice1);
        handler.afterConnectionEstablished(hostDevice2);
        handler.afterConnectionEstablished(guest);

        assertThat(registry.closeNotAllowed(HOST_ID)).isEqualTo(2);

        verify(hostDevice1).close(ChatWebSocketSessionRegistry.CHAT_NOT_ALLOWED);
        verify(hostDevice2).close(ChatWebSocketSessionRegistry.CHAT_NOT_ALLOWED);
        verify(guest, never()).close(any());
        assertThat(ChatWebSocketSessionRegistry.CHAT_NOT_ALLOWED.getCode()).isEqualTo(1008);
        assertThat(ChatWebSocketSessionRegistry.CHAT_NOT_ALLOWED.getReason()).isEqualTo("CHAT_NOT_ALLOWED");
    }

    @Test
    void 로컬_연결이_없는_회원을_닫아도_예외_없이_0을_돌려준다() {
        assertThat(registry.closeNotAllowed(UNKNOWN_ID)).isZero();
        assertThat(registry.connectedAmong(List.of(UNKNOWN_ID))).isEmpty();
    }

    @Test
    void 회원_ID로_읽을_수_없는_연결은_회원별_목록에_넣지_않는다() throws Exception {
        handler.afterConnectionEstablished(session("s-1", null));
        handler.afterConnectionEstablished(session("s-2", "not-a-number"));

        assertThat(registry.connectedUserIds()).isEmpty();
    }

    @Test
    void 연결_시작_처리가_실패하면_회원별_목록에서도_지운다() throws Exception {
        WebSocketSession host = session("s-1", String.valueOf(HOST_ID));
        doThrow(new IllegalStateException("시작 실패")).when(delegate).afterConnectionEstablished(host);

        assertThatThrownBy(() -> handler.afterConnectionEstablished(host)).isInstanceOf(IllegalStateException.class);

        assertThat(registry.connectedUserIds()).isEmpty();
    }

    /**
     * 등록 스레드가 연결 목록에 넣는 도중(회원 ID를 읽는 시점)에 다른 스레드가 연결을 닫고 닫힘 처리를 끝낸다.
     * 수정 전에는 연결 목록에 넣은 뒤 회원별 목록에 넣기 전 이 시점에 닫힘 처리가 끼어들어, 닫힌 연결이 회원별 목록에 남았다.
     */
    @Test
    void 등록_도중_연결이_닫히면_어느_목록에도_남지_않는다() throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        CountDownLatch registering = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        Thread registerThread = Thread.currentThread();
        Principal principal = () -> String.valueOf(HOST_ID);
        WebSocketSession host = mock(WebSocketSession.class);
        when(host.getId()).thenReturn("s-1");
        when(host.isOpen()).thenAnswer(invocation -> open.get());
        when(host.getPrincipal()).thenAnswer(invocation -> {
            // 등록 스레드의 첫 조회에서만 멈추고, 그동안 다른 스레드가 연결을 닫는다
            if (Thread.currentThread() == registerThread && registering.getCount() > 0) {
                registering.countDown();
                assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return principal;
        });

        CompletableFuture<Void> closing = CompletableFuture.runAsync(() -> {
            try {
                assertThat(registering.await(5, TimeUnit.SECONDS)).isTrue();
                open.set(false);
                handler.afterConnectionClosed(host, CloseStatus.GOING_AWAY);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            } finally {
                closed.countDown();
            }
        });
        handler.afterConnectionEstablished(host);
        closing.get(5, TimeUnit.SECONDS);

        assertThat(registry.connectedUserIds()).isEmpty();
        assertThat(registry.connectedAmong(List.of(HOST_ID))).isEmpty();
        assertThat(registry.closeNotAllowed(HOST_ID)).isZero();
        verify(host, never()).close(any());
    }

    /**
     * 한 회원의 여러 연결이 동시에 열리고 닫힌다. 세 가지가 섞인다: 열린 채 남는 연결, 등록 뒤 닫히는 연결, 닫힌 뒤에야 등록이 도는 연결.
     * 회원별 목록과 연결 목록은 끝까지 열려 있는 연결만 가리켜야 하고, 그 연결이 모두 닫히면 회원 키도 사라져야 한다.
     */
    @Test
    void 한_회원의_여러_연결이_동시에_열리고_닫혀도_열린_연결만_남는다() throws Exception {
        int sessionCount = 64;
        int rounds = 20;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < rounds; round++) {
                ChatWebSocketSessionRegistry roundRegistry = new ChatWebSocketSessionRegistry(absent(), absent());
                WebSocketHandler roundHandler = roundRegistry.decorate(mock(WebSocketHandler.class));
                List<WebSocketSession> remaining = new ArrayList<>();
                List<Callable<Void>> tasks = new ArrayList<>();
                CountDownLatch start = new CountDownLatch(1);
                for (int i = 0; i < sessionCount; i++) {
                    AtomicBoolean open = new AtomicBoolean(true);
                    WebSocketSession session = switchableSession("s-" + i, String.valueOf(HOST_ID), open);
                    int kind = i % 3;
                    if (kind == 0) {
                        remaining.add(session);
                    }
                    tasks.add(() -> {
                        start.await();
                        if (kind == 2) {
                            // 닫힘 처리가 먼저 끝난 뒤 늦게 등록이 돈다
                            open.set(false);
                            roundHandler.afterConnectionClosed(session, CloseStatus.GOING_AWAY);
                            roundHandler.afterConnectionEstablished(session);
                            return null;
                        }
                        roundHandler.afterConnectionEstablished(session);
                        if (kind == 1) {
                            open.set(false);
                            roundHandler.afterConnectionClosed(session, CloseStatus.NORMAL);
                        }
                        return null;
                    });
                }
                List<Future<Void>> futures = new ArrayList<>();
                for (Callable<Void> task : tasks) {
                    futures.add(executor.submit(task));
                }
                start.countDown();
                for (Future<Void> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }

                assertThat(roundRegistry.connectedUserIds()).containsExactly(HOST_ID);
                assertThat(roundRegistry.closeNotAllowed(HOST_ID)).isEqualTo(remaining.size());

                List<Future<?>> closings = new ArrayList<>();
                for (WebSocketSession session : remaining) {
                    closings.add(executor.submit(() -> {
                        roundHandler.afterConnectionClosed(session, CloseStatus.NORMAL);
                        return null;
                    }));
                }
                for (Future<?> closing : closings) {
                    closing.get(10, TimeUnit.SECONDS);
                }
                assertThat(roundRegistry.connectedUserIds()).isEmpty();
                assertThat(roundRegistry.closeNotAllowed(HOST_ID)).isZero();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static WebSocketSession switchableSession(String id, String principalName, AtomicBoolean open) {
        WebSocketSession session = mock(WebSocketSession.class);
        Principal principal = () -> principalName;
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenAnswer(invocation -> open.get());
        when(session.getPrincipal()).thenReturn(principal);
        return session;
    }

    private static WebSocketSession session(String id, String principalName) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        if (principalName != null) {
            Principal principal = () -> principalName;
            when(session.getPrincipal()).thenReturn(principal);
        }
        return session;
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> absent() {
        return mock(org.springframework.beans.factory.ObjectProvider.class);
    }
}
