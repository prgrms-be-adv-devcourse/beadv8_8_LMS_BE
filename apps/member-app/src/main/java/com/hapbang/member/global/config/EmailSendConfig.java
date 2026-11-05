package com.hapbang.member.global.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 인증 메일을 뒤에서 보내는 작업 대기열 (#557). 요청은 메일을 맡기기만 하고 바로 응답한다.
 * Gmail이 느리거나 장애여도 요청 스레드가 묶이지 않고, 메일을 보내든 안 보내든(가입 여부) 응답 시간이 같아진다.
 * 대기열이 가득 차면 맡기기가 거절되고(TaskRejectedException) 부른 쪽이 503으로 답한다. 앱이 내려갈 때는 남은 메일을 10초까지 보낸다
 */
@Configuration
public class EmailSendConfig {

    /** 메일 발송 스레드 수 */
    static final int THREADS = 2;
    /** 보내지 못하고 기다리는 메일 수 상한 */
    static final int QUEUE_CAPACITY = 100;

    @Bean
    public Executor emailSendExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(THREADS);
        executor.setMaxPoolSize(THREADS);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("email-send-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
