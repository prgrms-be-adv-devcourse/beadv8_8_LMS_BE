package com.hapbang.customersupport.app;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 고객센터 배치 설정 ({@code customersupport.batch.*}, #279). 값을 주지 않으면 기본값이다.
 * 주기는 {@code customersupport.batch.filing-retry-cron}(기본 매분 15초)·{@code event-republish-cron}(기본 매분 5초)이다.
 *
 * @param pendingGrace 이 시간이 지난 것만 다시 처리한다. 요청 안에서 막 시도한 접수·커밋 직후 보내는 중인 이벤트와 겹치지 않게 한다. 기본 1분
 * @param batchSize    한 번에 처리할 최대 건수. 기본 100
 */
@ConfigurationProperties("customersupport.batch")
public record CustomerSupportBatchProperties(Duration pendingGrace, Integer batchSize) {

    public CustomerSupportBatchProperties {
        pendingGrace = pendingGrace == null ? Duration.ofMinutes(1) : pendingGrace;
        batchSize = batchSize == null ? 100 : batchSize;
    }
}
