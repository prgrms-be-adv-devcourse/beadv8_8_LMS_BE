package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;

/**
 * 실시간 전달 발행 스레드(N17-1, ND-205). 스레드 1개가 줄을 묶음으로 비운다. 한 묶음에서 예외가 나도 스레드는 멈추지 않는다.
 * 멈추면 스레드가 끝나고 남은 줄은 보내지 않는다(지금 실행기와 같다). Kafka 리스너보다 먼저 시작하고 나중에 멈춘다.
 */
class NotificationRealtimeWorkerTest {

    private final NotificationRealtimeRelay relay = mock(NotificationRealtimeRelay.class);
    private final NotificationRealtimeWorker worker = new NotificationRealtimeWorker(relay);

    @AfterEach
    void stop() {
        worker.stop();
    }

    @Test
    void 시작하면_스레드_하나가_줄을_계속_비운다() throws Exception {
        worker.start();

        assertThat(worker.isRunning()).isTrue();
        verify(relay, timeout(2000).atLeast(3)).deliverPending(any());
    }

    @Test
    void 한_묶음에서_예외가_나도_스레드는_계속_돈다() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(relay.deliverPending(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
            }
            return 0;
        });

        worker.start();

        verify(relay, timeout(2000).atLeast(3)).deliverPending(any());
    }

    @Test
    void 멈추면_스레드가_끝나고_Relay를_닫아_남은_줄은_버린다() throws Exception {
        worker.start();
        verify(relay, timeout(2000).atLeast(1)).deliverPending(any());

        worker.stop();

        assertThat(worker.isRunning()).isFalse();
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(
                thread -> thread.getName().startsWith(NotificationRealtimeWorker.THREAD_NAME) && thread.isAlive());
        verify(relay, atLeast(1)).close();
    }

    @Test
    void 시작하면_Relay를_다시_연다() {
        worker.start();

        verify(relay).open();
    }

    @Test
    void Kafka_리스너보다_먼저_시작하고_나중에_멈춘다() {
        assertThat(worker.getPhase()).isLessThan(AbstractMessageListenerContainer.DEFAULT_PHASE);
    }
}
