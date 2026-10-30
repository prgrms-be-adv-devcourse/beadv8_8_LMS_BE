package com.hapbang.chat.in.stream;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.hapbang.chat.app.ChatDeliveryAvailability;
import com.hapbang.chat.app.ChatDeliveryProperties;
import com.hapbang.chat.app.ChatDeliveryQueue;
import com.hapbang.chat.app.ChatJobStream;
import com.hapbang.chat.app.ChatOutboxRelay;
import com.hapbang.chat.app.ChatStreamJobProcessor;
import com.hapbang.chat.app.ChatStreamReconstructor;
import com.hapbang.chat.app.ChatStreamTrimmer;

/**
 * 실행기를 멈출 때 worker가 실제로 끝났는지 확인하고, 끝나지 않은 worker가 있으면 다시 시작하지 않는다.
 * 처리 빈은 목으로 두고, 한 worker의 새 작업 읽기를 인터럽트로도 깨지 않는 느린 I/O처럼 멈춘다.
 */
class ChatDeliveryPipelineRunnerShutdownTest {

    private static final String STUCK = "test:delivery:1";

    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch stuckEntered = new CountDownLatch(1);
    private final AtomicInteger stuckPollsAfterRelease = new AtomicInteger();
    private volatile boolean released;

    private final ChatStreamJobProcessor processor = mock(ChatStreamJobProcessor.class);
    private final ChatDeliveryPipelineRunner runner = new ChatDeliveryPipelineRunner(mock(ChatOutboxRelay.class),
            processor, mock(ChatStreamReconstructor.class), mock(ChatStreamTrimmer.class),
            mock(ChatDeliveryQueue.class), properties(), mock(ChatDeliveryAvailability.class));

    @AfterEach
    void tearDown() {
        release.countDown();
        runner.stop();
    }

    @Test
    void 느린_읽기에_멈춘_worker가_끝나기_전에는_다시_시작하지_않고_worker_수가_설정을_넘지_않는다() throws Exception {
        AtomicInteger firstStuckCall = new AtomicInteger();
        when(processor.pollNew(any(), anyString())).thenAnswer(invocation -> {
            String consumer = invocation.getArgument(1);
            if (STUCK.equals(consumer) && firstStuckCall.getAndIncrement() == 0) {
                stuckEntered.countDown();
                // 인터럽트로도 깨지 않는 느린 Redis·DB 호출(블로킹 소켓 I/O)
                boolean interrupted = false;
                while (release.getCount() > 0) {
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return 0;
            }
            if (STUCK.equals(consumer) && released) {
                stuckPollsAfterRelease.incrementAndGet();
            }
            Thread.sleep(10);
            return 0;
        });
        runner.start();
        assertThat(stuckEntered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(runner.aliveWorkerCount()).isEqualTo(3);

        long started = System.nanoTime();
        runner.stop();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));

        // 멈춘 worker 하나가 남았다. 끝날 때까지 다시 시작하지 않는다
        assertThat(runner.isRunning()).isFalse();
        assertThat(runner.aliveWorkerCount()).isEqualTo(1);
        assertThatThrownBy(runner::start).isInstanceOf(IllegalStateException.class).hasMessageContaining(STUCK);
        assertThat(runner.aliveWorkerCount()).isEqualTo(1);

        // 느린 호출이 끝나면 옛 worker는 자기 세대가 꺼졌으므로 다시 읽지 않고 끝난다
        released = true;
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> runner.aliveWorkerCount() == 0);
        assertThat(stuckPollsAfterRelease).hasValue(0);

        runner.start();
        assertThat(runner.isRunning()).isTrue();
        assertThat(runner.aliveWorkerCount()).isEqualTo(3);
        runner.stop();
        assertThat(runner.aliveWorkerCount()).isZero();
    }

    @Test
    void 멈춘_worker는_인터럽트로_깨어나_제한_시간_안에_끝난다() {
        when(processor.pollNew(any(), anyString())).thenAnswer(invocation -> {
            // 새 작업이 없을 때의 블로킹 읽기·멈춤 대기
            Thread.sleep(Duration.ofSeconds(30));
            return 0;
        });
        runner.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> runner.aliveWorkerCount() == 3);

        runner.stop();

        assertThat(runner.aliveWorkerCount()).isZero();
        runner.start();
        assertThat(runner.aliveWorkerCount()).isEqualTo(3);
    }

    private static ChatDeliveryProperties properties() {
        Map<String, String> values = Map.of(
                "hapbang.chat.delivery.instance-id", "test",
                "hapbang.chat.delivery.consumer.delivery-workers", "2",
                "hapbang.chat.delivery.consumer.control-workers", "1",
                "hapbang.chat.delivery.shutdown-await", "500ms",
                "hapbang.chat.delivery.relay.poll-interval", "1h",
                "hapbang.chat.delivery.reclaim.interval", "1h",
                "hapbang.chat.delivery.trim.interval", "1h");
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("hapbang.chat.delivery", ChatDeliveryProperties.class)
                .get();
    }
}
