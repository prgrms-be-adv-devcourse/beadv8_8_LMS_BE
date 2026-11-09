package com.hapbang.auxiliary.chatbot.app;

import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import com.hapbang.auxiliary.chatbot.domain.CopyTimes;
import com.hapbang.auxiliary.chatbot.domain.SyncType;

/**
 * 챗봇 모듈 설정 (#716).
 * <p>
 * 시계는 같은 앱의 다른 모듈과 겹치지 않게 이름({@value #CLOCK})으로만 주입한다(기본 후보가 아님). 테스트가 이 이름으로 바꿔 끼운다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatbotSyncProperties.class)
public class ChatbotConfig {

    public static final String CLOCK = "chatbotClock";
    public static final String SYNC_EXECUTOR = "chatbotSyncExecutor";

    @Bean(name = CLOCK, defaultCandidate = false)
    Clock chatbotClock() {
        return Clock.system(CopyTimes.KST);
    }

    /**
     * 관리자 싱크를 돌리는 스레드. 종류마다 락이 하나라 동시에 도는 실행은 종류 수를 넘지 않는다.
     * 앱 공통 실행기 대신 따로 두어 다른 모듈의 비동기 작업과 섞이지 않게 한다.
     * Boot builder로 만들어 trace decorator가 붙는다(관리자 요청의 traceId가 싱크 로그에 이어진다, docs/dev/logging.md 스레드 경계).
     * builder가 가져오는 {@code spring.task.execution.simple.*} 기본값에 흔들리지 않게 동작 값은 모두 정한다.
     */
    @Bean(name = SYNC_EXECUTOR, defaultCandidate = false)
    SimpleAsyncTaskExecutor chatbotSyncExecutor(SimpleAsyncTaskExecutorBuilder builder) {
        return builder.threadNamePrefix("chatbot-sync-")
                .virtualThreads(false)
                .concurrencyLimit(SyncType.values().length)
                .rejectTasksWhenLimitReached(false)
                .cancelRemainingTasksOnClose(false)
                .taskTerminationTimeout(Duration.ofSeconds(30))
                .build();
    }
}
