package com.hapbang.chat.in.websocket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 앱 전체 송신 대기열이 넘치면, clientOutboundChannel과 같은 Spring 채널에서 보내는 쪽을 막지 않고(호출 스레드 실행 없음)
 * 버린 프레임의 연결만 닫는다.
 */
class ChatOutboundSendBudgetTest {

    private final ChatWebSocketSessionRegistry registry = mock(ChatWebSocketSessionRegistry.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    // 송신 스레드 1개 + 대기열 1개
    private final ChatOutboundSendBudget budget = new ChatOutboundSendBudget(registry, meterRegistry, 1, 1, 10);
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        budget.destroy();
    }

    @Test
    void 송신_대기열이_넘치면_보내는_쪽을_막지_않고_버린_프레임의_연결만_OUTBOUND_OVERFLOW로_닫는다() throws Exception {
        ExecutorSubscribableChannel outbound = new ExecutorSubscribableChannel(budget.executor());
        List<String> handledSessions = new CopyOnWriteArrayList<>();
        List<Thread> handlerThreads = new CopyOnWriteArrayList<>();
        CountDownLatch writing = new CountDownLatch(1);
        outbound.subscribe(message -> {
            handledSessions.add(SimpMessageHeaderAccessor.getSessionId(message.getHeaders()));
            handlerThreads.add(Thread.currentThread());
            writing.countDown();
            try {
                // 느린 소켓 쓰기
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        when(registry.closeSession(anyString(), any())).thenReturn(true);

        outbound.send(frame("s-1"));
        assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
        outbound.send(frame("s-2"));
        long started = System.nanoTime();
        assertThat(outbound.send(frame("s-3"))).isTrue();
        outbound.send(frame("s-3"));
        // 넘친 프레임을 호출 스레드에서 실행하지 않아 바로 돌아온다
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1_000);

        verify(registry, timeout(2_000)).closeSession("s-3", ChatWebSocketSessionRegistry.OUTBOUND_OVERFLOW);
        assertThat(meterRegistry.counter(ChatOutboundSendBudget.REJECTED_METRIC).count()).isEqualTo(2);

        release.countDown();
        Thread.sleep(300);
        assertThat(handledSessions).containsExactly("s-1", "s-2");
        assertThat(handlerThreads).noneMatch(thread -> thread == Thread.currentThread());
        verify(registry, never()).closeSession(eq("s-1"), any());
        verify(registry, never()).closeSession(eq("s-2"), any());
        assertThat(ChatWebSocketSessionRegistry.OUTBOUND_OVERFLOW.getCode()).isNotEqualTo(1008);
    }

    private static Message<byte[]> frame(String sessionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
