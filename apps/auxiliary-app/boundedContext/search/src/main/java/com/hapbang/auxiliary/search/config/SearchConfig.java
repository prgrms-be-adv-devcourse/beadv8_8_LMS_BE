package com.hapbang.auxiliary.search.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/** 검색 설정: 순위 계수(#771), 처음 적재·재색인 실행기(#773) */
@Configuration(proxyBeanMethods = false)
@ConditionalOnSearchEnabled
@EnableConfigurationProperties(SearchRankingProperties.class)
public class SearchConfig {

    public static final String REINDEX_EXECUTOR = "searchReindexExecutor";

    /**
     * 처음 적재·재색인을 돌리는 스레드(가상 스레드). 몇 분 걸릴 수 있어 앱 공용 스케줄러 스레드(2개)에서 떼어 낸다.
     * <ul>
     *     <li>Boot builder로 만들어 trace decorator가 붙는다. 10분 확인({@code @Scheduled})의 traceId가 재색인 로그에 이어진다
     *     ({@code docs/dev/logging.md} 스레드 경계, PR #845 리뷰).</li>
     *     <li>개수 제한은 두지 않는다(제한에 걸리면 넘기는 스케줄러 스레드가 기다린다). 같은 pod에서 겹쳐 도는 것은 lock(773_001)이 막는다.</li>
     *     <li>앱이 내려가면 돌던 재색인을 끊는다. 끊긴 재색인은 alias를 옛 그대로 두고, 남은 index는 다음 확인이 지운다.</li>
     *     <li>builder가 가져오는 {@code spring.task.execution.simple.*} 기본값에 흔들리지 않게 동작 값은 모두 정한다.</li>
     * </ul>
     */
    @Bean(name = REINDEX_EXECUTOR, defaultCandidate = false)
    SimpleAsyncTaskExecutor searchReindexExecutor(SimpleAsyncTaskExecutorBuilder builder) {
        return builder.threadNamePrefix("search-reindex-")
                .virtualThreads(true)
                .concurrencyLimit(SimpleAsyncTaskExecutor.UNBOUNDED_CONCURRENCY)
                .rejectTasksWhenLimitReached(false)
                .cancelRemainingTasksOnClose(true)
                .taskTerminationTimeout(Duration.ofSeconds(10))
                .build();
    }
}
