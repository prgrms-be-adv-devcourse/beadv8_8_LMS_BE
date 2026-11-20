package com.hapbang.auxiliary.notification.app;

import java.time.Duration;

import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * 관리자 Backfill 재실행을 돌리는 실행기(ND-092). 잠금 하나라 동시에 도는 실행은 하나다. Boot builder로 만들어 trace decorator가
 * 붙는다(관리자 요청의 traceId가 Backfill 로그에 이어진다, docs/dev/logging.md 스레드 경계). builder의
 * {@code spring.task.execution.simple.*} 기본값에 흔들리지 않게 동작 값은 모두 정한다.
 * virtual thread가 아니라 daemon platform thread다. JDK 25에서는 클래스를 처음 읽는 virtual thread가 carrier를 붙잡아 앱 전체가
 * 멈출 수 있다(#942, {@code docs/adr/0003-no-virtual-threads-on-jdk25.md}).
 */
@Configuration(proxyBeanMethods = false)
public class NotificationAdminBackfillExecutorConfig {

    public static final String EXECUTOR = "notificationAdminBackfillExecutor";

    @Bean(name = EXECUTOR, defaultCandidate = false)
    SimpleAsyncTaskExecutor notificationAdminBackfillExecutor(SimpleAsyncTaskExecutorBuilder builder) {
        return builder.threadNamePrefix("notification-admin-backfill-")
                .virtualThreads(false)
                .additionalCustomizers(executor -> executor.setDaemon(true))
                .concurrencyLimit(1)
                .rejectTasksWhenLimitReached(true)
                .cancelRemainingTasksOnClose(false)
                .taskTerminationTimeout(Duration.ofSeconds(30))
                .build();
    }
}
