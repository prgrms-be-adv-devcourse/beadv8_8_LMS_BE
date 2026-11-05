package com.hapbang.member.out;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 인증 메일을 뒤에서 보내는 작업 대기열 (#557). 요청은 메일을 맡기기만 하고 바로 응답한다.
 * Gmail이 느리거나 장애여도 요청 스레드가 묶이지 않고, 메일을 보내든 안 보내든(가입 여부) 응답 시간이 같아진다.
 * <ul>
 *     <li>전용 스레드 {@link #THREADS}개, 기다리는 메일 최대 {@link #QUEUE_CAPACITY}건. 가득 차면 {@link #submit}이
 *     {@link java.util.concurrent.RejectedExecutionException}을 던지고 부른 쪽이 503으로 답한다.</li>
 *     <li>Spring 빈 타입 {@code Executor}로 드러내지 않는다. {@code Executor} 빈이 있으면 Spring Boot가 기본 작업 실행기
 *     ({@code applicationTaskExecutor})를 만들지 않아, 회원 이벤트 Kafka 발행(Modulith 비동기) 같은 다른 비동기 작업이 trace를
 *     잃는다(#630 테스트가 잡음).</li>
 *     <li>맡긴 스레드의 trace·로그 정보(MDC)를 메일 스레드로 넘긴다.</li>
 *     <li>앱이 내려갈 때 남은 메일은 10초까지 보낸다.</li>
 * </ul>
 */
@Component
public class EmailSendQueue implements DisposableBean {

    /** 메일 발송 스레드 수 */
    static final int THREADS = 2;
    /** 보내지 못하고 기다리는 메일 수 상한 */
    static final int QUEUE_CAPACITY = 100;

    private final ThreadPoolTaskExecutor executor;

    public EmailSendQueue() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(THREADS);
        executor.setMaxPoolSize(THREADS);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("email-send-");
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
    }

    /** 메일 보내기를 맡긴다. 대기열이 가득 차면 RejectedExecutionException */
    public void submit(Runnable task) {
        executor.execute(task);
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
