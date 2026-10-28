package com.hapbang.appearance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.hapbang.appearance.domain.HpTaskRetryPolicy;

/**
 * 출연 배치 설정 ({@code appearance.batch.*}). 실행 주기(cron)는 배치 클래스의 {@code @Scheduled}가 같은 키를 직접 읽는다.
 *
 * @param batchSize         한 번 실행할 때 처리할 최대 건수. 나머지는 다음 주기에 처리한다
 * @param pendingGrace      방금 시작된 처리와 겹치지 않도록 기다리는 시간. 이보다 오래된 보류 처리 중 요청서·모집글과,
 *                          처음 시도 전인 HP 작업은 이 시간이 지나야 배치가 가져간다
 * @param retryBaseDelay    HP 작업 첫 실패 후 다음 시도까지 간격
 * @param retryMaxDelay     HP 작업 재시도 간격 상한
 * @param retryMaxAttempts  HP 작업 최대 시도 횟수 (넘으면 격리)
 * @param retryJitterRatio  재시도 간격에 더할 무작위 비율의 최대값
 */
@ConfigurationProperties(prefix = "appearance.batch")
public record AppearanceBatchProperties(
        int batchSize,
        Duration pendingGrace,
        Duration retryBaseDelay,
        Duration retryMaxDelay,
        int retryMaxAttempts,
        double retryJitterRatio
) {

    public AppearanceBatchProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("배치 처리 건수는 1 이상이어야 합니다.");
        }
        if (pendingGrace == null || pendingGrace.isNegative()) {
            throw new IllegalArgumentException("대기 시간은 0 이상이어야 합니다.");
        }
    }

    public HpTaskRetryPolicy toRetryPolicy() {
        return new HpTaskRetryPolicy(retryBaseDelay, retryMaxDelay, retryMaxAttempts, retryJitterRatio);
    }
}
