package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
    void 한_묶음에서_Error가_나도_스레드는_계속_돈다() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(relay.deliverPending(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new NoClassDefFoundError("boom");
            }
            return 0;
        });

        worker.start();

        verify(relay, timeout(2000).atLeast(3)).deliverPending(any());
        assertThat(worker.isRunning()).isTrue();
    }

    @Test
    void 멈춤_요청_없이_interrupt되어도_스레드는_계속_돈다() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(relay.deliverPending(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new InterruptedException("stray");
            }
            return 0;
        });

        worker.start();

        verify(relay, timeout(2000).atLeast(3)).deliverPending(any());
        assertThat(worker.isRunning()).isTrue();
    }

    @Test
    void 묶음을_처리하는_중에_멈추면_interrupt하지_않고_그_묶음을_끝낸_뒤_멈춘다() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        when(relay.deliverPending(any())).thenAnswer(invocation -> {
            if (started.getCount() == 0) {
                return 0;
            }
            started.countDown();
            long until = System.nanoTime() + Duration.ofMillis(300).toNanos();
            while (System.nanoTime() < until) {
                interrupted.compareAndSet(false, Thread.currentThread().isInterrupted());
            }
            finished.set(true);
            return 1;
        });
        worker.start();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        worker.stop();

        assertThat(interrupted).isFalse();
        assertThat(finished).isTrue();
        verify(relay).close();
    }

    @Test
    void 멈춘_뒤_다시_시작할_수_있고_발행_스레드는_하나다() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        when(relay.deliverPending(any())).thenAnswer(invocation -> concurrent(inFlight, maxInFlight));

        worker.start();
        verify(relay, timeout(2000).atLeast(1)).deliverPending(any());
        worker.stop();
        worker.start();
        verify(relay, timeout(2000).atLeast(3)).deliverPending(any());

        assertThat(worker.isRunning()).isTrue();
        assertThat(maxInFlight.get()).isEqualTo(1);
        InOrder order = inOrder(relay);
        order.verify(relay).open();
        order.verify(relay).close();
        order.verify(relay).open();
        verify(relay, times(1)).close(); // 이전 멈춤의 닫기는 새로 연 Relay를 다시 닫지 않는다
    }

    @Test
    void 이전_스레드가_대기_시간_안에_끝나지_않았으면_다시_시작하지_않는다() throws Exception {
        NotificationRealtimeWorker shortWait = new NotificationRealtimeWorker(relay, Duration.ofMillis(100));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        when(relay.deliverPending(any())).thenAnswer(invocation -> {
            started.countDown();
            awaitIgnoringInterrupt(release);
            return concurrent(inFlight, maxInFlight);
        });
        try {
            shortWait.start();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            shortWait.stop(); // 이전 스레드는 아직 묶음을 처리 중이다

            assertThatThrownBy(shortWait::start).isInstanceOf(IllegalStateException.class);
            assertThat(shortWait.isRunning()).isFalse();

            release.countDown(); // 이전 스레드가 끝난 뒤에는 다시 시작한다
            shortWait.start();
            verify(relay, timeout(2000).atLeast(3)).deliverPending(any());
            assertThat(maxInFlight.get()).isEqualTo(1);
        } finally {
            release.countDown();
            shortWait.stop();
        }
    }

    @Test
    void 여러_스레드가_동시에_시작과_멈춤을_불러도_발행_스레드는_동시에_하나뿐이다() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        when(relay.deliverPending(any())).thenAnswer(invocation -> concurrent(inFlight, maxInFlight));
        ExecutorService callers = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                boolean start = i % 2 == 0;
                calls.add(callers.submit(() -> {
                    if (start) {
                        worker.start();
                    } else {
                        worker.stop();
                    }
                }));
            }
            for (Future<?> call : calls) {
                call.get(30, TimeUnit.SECONDS);
            }
        } finally {
            callers.shutdownNow();
        }

        assertThat(maxInFlight.get()).isLessThanOrEqualTo(1);
    }

    /** 동시에 몇 개의 발행 스레드가 묶음을 처리하는지 잰다 */
    private static int concurrent(AtomicInteger inFlight, AtomicInteger maxInFlight) throws InterruptedException {
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            Thread.sleep(5);
            return 0;
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        while (true) {
            try {
                latch.await();
                return;
            } catch (InterruptedException ignored) {
                // 끝나지 않는 이전 스레드를 흉내 낸다
            }
        }
    }

    @Test
    void Kafka_리스너보다_먼저_시작하고_나중에_멈춘다() {
        assertThat(worker.getPhase()).isLessThan(AbstractMessageListenerContainer.DEFAULT_PHASE);
    }
}
