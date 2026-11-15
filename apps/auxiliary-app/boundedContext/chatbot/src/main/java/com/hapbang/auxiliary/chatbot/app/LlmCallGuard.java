package com.hapbang.auxiliary.chatbot.app;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.FallbackReason;
import com.hapbang.auxiliary.chatbot.config.ChatbotLlmConfig;
import com.hapbang.auxiliary.chatbot.config.ChatbotLlmProperties;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.annotation.PreDestroy;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * 챗봇 LLM 호출 장치(#718 공통 기반, #715 FAQ 대화에서 꺼냄). FAQ 대화·강의 추천 설명·인플루언서 추천·자유 질문이 모두 이것을 거친다.
 * <ul>
 *     <li>회로 하나({@link ChatbotLlmConfig#CIRCUIT_BREAKER})와 동시 호출 상한 하나({@code hapbang.chatbot.llm.max-concurrent-calls})를
 *     앱 전체 기능이 나눠 쓴다. 기능마다 따로 두면 공급자 장애 때 기능 수만큼 기다리고, 상한이 기능 수만큼 늘어난다.</li>
 *     <li>잡지 못하면 이유({@link FallbackReason#MODEL_OFF}·{@link FallbackReason#CIRCUIT_OPEN}·{@link FallbackReason#BUSY})를
 *     돌려준다. 이때 기능은 LLM 없이 답한다(fallback). 로그·지표는 기능 쪽이 남긴다.</li>
 *     <li>한 번에 받기({@link #call})는 별도 스레드에서 부르고 시간 상한까지 기다린다. 스트림은 {@link #acquire}로 허가만 받고
 *     기능이 Reactor로 시간을 자른 뒤 결과를 직접 알린다(Resilience4j TimeLimiter는 Flux에 걸리지 않는다).</li>
 *     <li>별도 스레드는 Boot builder로 만든 가상 스레드 executor다. 앱의 trace decorator가 붙어 LLM 호출·Tool 로그에 요청의
 *     {@code traceId}가 찍힌다(docs/dev/logging.md "스레드 경계"). 동시 수는 허가가 제한한다.</li>
 * </ul>
 */
@Component
public class LlmCallGuard {

    private final CircuitBreaker circuitBreaker;
    private final Semaphore permits;
    private final SimpleAsyncTaskExecutor executor;
    private final Scheduler scheduler;

    LlmCallGuard(@Qualifier(ChatbotLlmConfig.CIRCUIT_BREAKER) CircuitBreaker circuitBreaker, ChatbotLlmProperties properties,
            SimpleAsyncTaskExecutorBuilder executorBuilder) {
        this.circuitBreaker = circuitBreaker;
        this.permits = new Semaphore(properties.maxConcurrentCalls());
        // builder 기본값(spring.task.execution.simple.*) 중 동작에 영향을 주는 값은 모두 정한다. 동시 수는 permits가 제한한다
        this.executor = executorBuilder
                .threadNamePrefix("chatbot-llm-")
                .virtualThreads(true)
                .concurrencyLimit(SimpleAsyncTaskExecutor.UNBOUNDED_CONCURRENCY)
                .rejectTasksWhenLimitReached(false)
                .taskTerminationTimeout(Duration.ZERO)
                .cancelRemainingTasksOnClose(true)
                .build();
        this.scheduler = Schedulers.fromExecutor(executor);
    }

    /**
     * 허가를 받아 별도 스레드에서 부르고 {@code timeout}까지 기다린다. 성공·실패·시간 초과를 회로에 기록한다.
     *
     * @param modelAvailable 모델이 있는 실행인지. 없으면 부르지 않고 {@link FallbackReason#MODEL_OFF}
     */
    public <T> Result<T> call(boolean modelAvailable, Duration timeout, Callable<T> task) {
        Permit permit = acquire(modelAvailable);
        if (permit.blocked() != null) {
            return Result.blocked(permit.blocked());
        }
        Future<T> future;
        try {
            future = executor.submit(task);
        } catch (RuntimeException e) {
            // 스레드를 만들지 못한 것(앱 종료 중 등)은 LLM 장애가 아니라 회로에 기록하지 않고 자리만 돌려준다
            permit.cancelled();
            return new Result<>(null, FallbackReason.ERROR, e);
        }
        try {
            T value = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            permit.succeeded();
            return new Result<>(value, null, null);
        } catch (TimeoutException e) {
            future.cancel(true);
            permit.failed(e);
            return new Result<>(null, FallbackReason.TIMEOUT, e);
        } catch (ExecutionException e) {
            permit.failed(e.getCause());
            return new Result<>(null, FallbackReason.ERROR, e.getCause());
        } catch (InterruptedException e) {
            future.cancel(true);
            permit.cancelled();
            Thread.currentThread().interrupt();
            return new Result<>(null, FallbackReason.ERROR, e);
        }
    }

    /**
     * 회로 허가와 동시 호출 자리를 잡는다. 스트림처럼 시간을 기능이 자르는 호출용이다. 받은 허가는 성공·실패·취소 중 하나로 반드시 돌려준다.
     *
     * @param modelAvailable 모델이 있는 실행인지. 없으면 {@link FallbackReason#MODEL_OFF}로 막힌 허가
     */
    public Permit acquire(boolean modelAvailable) {
        return new Permit(modelAvailable);
    }

    /** LLM 호출·fallback 검색처럼 블로킹 작업을 Reactor 흐름에서 돌릴 scheduler(trace가 이어진다) */
    public Scheduler scheduler() {
        return scheduler;
    }

    @PreDestroy
    void close() {
        scheduler.dispose();
        executor.close();
    }

    /**
     * 한 번에 받기의 결과. {@link #reason()}이 null이면 성공이다.
     *
     * @param value  모델 응답(성공일 때)
     * @param reason LLM 없이 답해야 하는 이유. 성공이면 null
     * @param error  실패·시간 초과의 원인. 막혔거나 성공이면 null
     */
    public record Result<T>(@Nullable T value, @Nullable FallbackReason reason, @Nullable Throwable error) {

        static <T> Result<T> blocked(FallbackReason reason) {
            return new Result<>(null, reason, null);
        }

        public boolean succeeded() {
            return reason == null;
        }
    }

    /**
     * LLM 호출 한 번의 회로 허가와 동시 호출 자리. 만들 때 둘을 잡고, 성공·실패·취소 중 처음 한 번만 회로에 기록하고 자리를 돌려준다.
     * 잡지 못했으면 {@link #blocked()}에 이유가 있고 돌려줄 것이 없다.
     */
    public final class Permit {

        private final @Nullable FallbackReason blocked;
        private final long start = System.nanoTime();
        private final AtomicBoolean settled = new AtomicBoolean(false);

        private Permit(boolean modelAvailable) {
            this.blocked = tryAcquire(modelAvailable);
            if (blocked != null) {
                settled.set(true);
            }
        }

        /** 잡지 못한 이유. 잡았으면 null */
        public @Nullable FallbackReason blocked() {
            return blocked;
        }

        public void succeeded() {
            if (settled.compareAndSet(false, true)) {
                circuitBreaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                permits.release();
            }
        }

        public void failed(Throwable error) {
            if (settled.compareAndSet(false, true)) {
                circuitBreaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, error);
                permits.release();
            }
        }

        /** 사용자가 끊었거나 스레드가 중단됐다. 성공도 실패도 아니라 자리만 돌려준다 */
        public void cancelled() {
            if (settled.compareAndSet(false, true)) {
                circuitBreaker.releasePermission();
                permits.release();
            }
        }

        private @Nullable FallbackReason tryAcquire(boolean modelAvailable) {
            if (!modelAvailable) {
                return FallbackReason.MODEL_OFF;
            }
            if (!circuitBreaker.tryAcquirePermission()) {
                return FallbackReason.CIRCUIT_OPEN;
            }
            if (!permits.tryAcquire()) {
                circuitBreaker.releasePermission();
                return FallbackReason.BUSY;
            }
            return null;
        }
    }
}
