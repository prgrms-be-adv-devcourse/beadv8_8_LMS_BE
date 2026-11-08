package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.hapbang.chat.app.dto.ReadChangedFanout;

/**
 * 안 읽은 사람 수 변경 신호(설계 Q36): 트랜잭션 안이면 커밋 뒤에만 내고 롤백이면 내지 않는다. 발행은 전용 실행기에서 해 커밋한
 * 스레드를 막지 않고, 대기열이 가득 차면 버린다. 발행 실패는 변경을 실패시키지 않는다. 발행 빈이 없으면 아무것도 하지 않는다.
 */
class ChatReadCountSignalsTest {

    private static final long TIMEOUT_MILLIS = 2_000;

    private final ChatReadCountPublisher publisher = mock(ChatReadCountPublisher.class);
    private final ChatReadCountSignals signals = new ChatReadCountSignals(provider(publisher), new ThreadPoolTaskExecutorBuilder(), 1, 1);

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        signals.destroy();
    }

    @Test
    void 트랜잭션_밖이면_바로_실행기에_넣어_낸다() {
        signals.readChanged(10L);
        signals.membershipChanged(11L, 4);

        verify(publisher, timeout(TIMEOUT_MILLIS)).publish(new ReadChangedFanout(10L, 0));
        verify(publisher, timeout(TIMEOUT_MILLIS)).publish(new ReadChangedFanout(11L, 4));
    }

    @Test
    void 트랜잭션_안이면_커밋된_뒤에만_낸다() {
        TransactionSynchronizationManager.initSynchronization();

        signals.membershipChanged(10L, 3);
        verify(publisher, after(300).never()).publish(any());

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(publisher, timeout(TIMEOUT_MILLIS)).publish(new ReadChangedFanout(10L, 3));
    }

    @Test
    void 롤백되면_내지_않는다() {
        TransactionSynchronizationManager.initSynchronization();

        signals.readChanged(10L);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(publisher, after(300).never()).publish(any());
    }

    @Test
    void 발행이_멈춰도_커밋한_스레드는_기다리지_않고_대기열이_가득_차면_버린다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch publishing = new CountDownLatch(1);
        doAnswer(invocation -> {
            publishing.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(publisher).publish(any());
        TransactionSynchronizationManager.initSynchronization();
        signals.readChanged(1L);
        signals.readChanged(2L);
        signals.readChanged(3L);

        long startedAt = System.nanoTime();
        // 첫 신호는 발행 중에 멈추고, 둘째는 대기열(1)에 들어가고, 셋째는 버린다
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> {
            sync.afterCommit();
            if (sync == TransactionSynchronizationManager.getSynchronizations().getFirst()) {
                awaitQuietly(publishing);
            }
        });
        Duration commitThread = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(commitThread).isLessThan(Duration.ofSeconds(2));
        release.countDown();
        verify(publisher, timeout(TIMEOUT_MILLIS)).publish(new ReadChangedFanout(1L, 0));
        verify(publisher, timeout(TIMEOUT_MILLIS)).publish(new ReadChangedFanout(2L, 0));
        verify(publisher, after(300).never()).publish(new ReadChangedFanout(3L, 0));
    }

    @Test
    void 발행이_실패해도_예외를_던지지_않고_다음_신호는_낸다() {
        doThrow(new IllegalStateException("Redis 장애")).doNothing().when(publisher).publish(any());

        assertThatCode(() -> signals.readChanged(10L)).doesNotThrowAnyException();
        verify(publisher, timeout(TIMEOUT_MILLIS)).publish(new ReadChangedFanout(10L, 0));
        signals.readChanged(11L);
        verify(publisher, timeout(TIMEOUT_MILLIS)).publish(new ReadChangedFanout(11L, 0));
    }

    @Test
    void 발행_빈이_없으면_아무것도_하지_않는다() {
        ChatReadCountSignals disabled = new ChatReadCountSignals(provider(null), new ThreadPoolTaskExecutorBuilder(), 1, 1);
        TransactionSynchronizationManager.initSynchronization();

        disabled.readChanged(10L);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        disabled.destroy();
    }

    @Test
    void 실행기는_Boot_builder의_task_decorator를_거쳐_신호를_넘긴_스레드의_trace를_이어받는다() {
        AtomicInteger decorated = new AtomicInteger();
        ChatReadCountSignals decoratedSignals = new ChatReadCountSignals(provider(publisher),
                new ThreadPoolTaskExecutorBuilder().taskDecorator(task -> {
                    decorated.incrementAndGet();
                    return task;
                }), 1, 1);

        decoratedSignals.readChanged(10L);

        verify(publisher, timeout(TIMEOUT_MILLIS)).publish(new ReadChangedFanout(10L, 0));
        assertThat(decorated).hasValue(1);
        decoratedSignals.destroy();
    }

    @Test
    void 실행기_수치는_1_이상이어야_한다() {
        assertThatThrownBy(() -> new ChatReadCountSignals(provider(publisher), new ThreadPoolTaskExecutorBuilder(), 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatReadCountSignals(provider(publisher), new ThreadPoolTaskExecutorBuilder(), 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            assertThat(latch.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).as("첫 신호의 발행 시작").isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ChatReadCountPublisher> provider(ChatReadCountPublisher publisher) {
        ObjectProvider<ChatReadCountPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(publisher);
        return provider;
    }
}
