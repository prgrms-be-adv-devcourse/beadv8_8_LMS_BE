package com.hapbang.member.out;

import java.time.Duration;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 인증 메일을 뒤에서 보내는 작업 대기열 (#557). 요청은 메일을 맡기기만 하고 바로 응답한다.
 * Gmail이 느리거나 장애여도 요청 스레드가 묶이지 않고, 메일을 보내든 안 보내든(가입 여부) 응답 시간이 같아진다.
 * <ul>
 *     <li>전용 스레드 {@link #THREADS}개, 기다리는 메일 최대 {@link #QUEUE_CAPACITY}건. 가득 차면 {@link #submit}이
 *     {@link java.util.concurrent.RejectedExecutionException}을 던지고 부른 쪽이 503으로 답한다.</li>
 *     <li>실행기는 Boot가 주입하는 builder로 만든다. 그래야 앱의 {@code traceContextTaskDecorator}(#630, Observation만 넘김)가 붙어
 *     메일 스레드 로그에 맡긴 요청의 {@code traceId}가 찍힌다(docs/dev/logging.md "스레드 경계"). builder의 기본값
 *     ({@code spring.task.execution.*}) 중 동작에 영향을 주는 값은 모두 여기서 정한다.</li>
 *     <li>실행기를 Spring 빈({@code Executor})으로 드러내지 않고 필드로만 든다. {@code Executor} 빈이 생기면 회원 이벤트 Kafka 발행
 *     (Modulith 비동기)이 trace를 잃었다(#630 테스트가 잡음).</li>
 *     <li>빈이 아니라 Spring이 초기화·종료해 주지 않으므로 직접 {@code initialize}·{@code shutdown}한다.
 *     앱이 내려갈 때 남은 메일은 10초까지 보낸다.</li>
 * </ul>
 */
@Component
public class EmailSendQueue implements DisposableBean {

    /** 메일 발송 스레드 수 */
    static final int THREADS = 2;
    /** 보내지 못하고 기다리는 메일 수 상한 */
    static final int QUEUE_CAPACITY = 100;
    /** 앱이 내려갈 때 남은 메일을 보내며 기다리는 시간 */
    static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    private final ThreadPoolTaskExecutor executor;

    public EmailSendQueue(ThreadPoolTaskExecutorBuilder executorBuilder) {
        executor = executorBuilder
                .threadNamePrefix("email-send-")
                .corePoolSize(THREADS)
                .maxPoolSize(THREADS)
                .queueCapacity(QUEUE_CAPACITY)
                .allowCoreThreadTimeOut(false)
                .awaitTermination(true)
                .awaitTerminationPeriod(SHUTDOWN_WAIT)
                .build();
        executor.initialize();
    }

    /** 메일 보내기를 맡긴다. 대기열이 가득 차면 RejectedExecutionException */
    public void submit(Runnable task) {
        executor.execute(task);
    }

    /** 새 메일은 받지 않고, 남은 메일은 {@link #SHUTDOWN_WAIT}까지 보낸 뒤 끝낸다 */
    @Override
    public void destroy() {
        executor.shutdown();
    }
}
