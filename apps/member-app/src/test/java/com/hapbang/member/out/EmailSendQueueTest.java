package com.hapbang.member.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;

/** 인증 메일 대기열 (#557) */
class EmailSendQueueTest {

    /** 앱의 traceContextTaskDecorator 자리. builder에 붙인 decorator가 메일 작업에도 붙는지 본다 */
    private static final ThreadLocal<Boolean> DECORATED = ThreadLocal.withInitial(() -> false);

    private final CountDownLatch release = new CountDownLatch(1);
    private EmailSendQueue queue;

    @AfterEach
    void tearDown() {
        release.countDown();
        if (queue != null) {
            queue.destroy();
        }
    }

    @Test
    @DisplayName("메일 전용 스레드에서 돌고, Boot builder에 붙은 decorator(trace 전달)가 적용된다")
    void runsOnMailThreadWithBuilderDecorator() throws InterruptedException {
        queue = new EmailSendQueue(new ThreadPoolTaskExecutorBuilder().taskDecorator(task -> () -> {
            DECORATED.set(true);
            try {
                task.run();
            } finally {
                DECORATED.remove();
            }
        }));
        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicBoolean decorated = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);

        queue.submit(() -> {
            threadName.set(Thread.currentThread().getName());
            decorated.set(DECORATED.get());
            done.countDown();
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName.get()).startsWith("email-send-");
        assertThat(decorated).isTrue();
    }

    @Test
    @DisplayName("스레드 2개가 모두 바쁘고 대기 100건이 차면 더 맡기지 않는다(RejectedExecutionException → 503)")
    void rejectsWhenFull() {
        queue = new EmailSendQueue(new ThreadPoolTaskExecutorBuilder());
        for (int i = 0; i < EmailSendQueue.THREADS + EmailSendQueue.QUEUE_CAPACITY; i++) {
            queue.submit(this::waitForRelease);
        }

        assertThatThrownBy(() -> queue.submit(() -> { }))
                .isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    @DisplayName("앱이 내려갈 때 이미 맡은 메일은 보내고 끝낸다")
    void finishesQueuedMailOnShutdown() throws InterruptedException {
        queue = new EmailSendQueue(new ThreadPoolTaskExecutorBuilder());
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean sent = new AtomicBoolean();
        queue.submit(() -> {
            started.countDown();
            sleepQuietly(200);
            sent.set(true);
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        queue.destroy();
        queue = null;

        assertThat(sent).isTrue();
    }

    private void waitForRelease() {
        try {
            release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
