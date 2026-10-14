package com.hapbang.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * {@code @Scheduled} 배치 실행 (#157). 배치 빈은 {@code @ConditionalOnSchedulingEnabled}로 켜고 끈다.
 * <p>
 * 채팅 STOMP가 TaskScheduler(messageBrokerTaskScheduler)를 등록하면 Boot 기본 스케줄러가 만들어지지 않는다.
 * 그러면 배치가 STOMP 스케줄러에서 실행되고 spring.task.scheduling 설정도 적용되지 않는다.
 * 그래서 배치가 쓰는 taskScheduler 빈을 직접 만든다(TaskScheduler가 여러 개면 이 이름의 빈을 쓴다).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {

    @Bean
    ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }
}
