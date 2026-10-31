package com.hapbang.lecture.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 강의 HP 작업 실행 설정 ({@code lecture.hp-task.*}, D57 (7)). 값을 주지 않으면 기본값이다.
 * 재시도 배치 주기는 {@code lecture.hp-task.retry-cron}(기본 1분마다)으로 {@code LectureHpTaskBatch}가 읽는다.
 *
 * @param lease              선점 유지 시간. 호출을 끊는 제한 시간이 아니라 결과 기록 권한과 재선점 시점을 정한다. 기본 5분
 * @param batchSize          재시도 배치가 한 번에 실행할 작업 수. 기본 100
 * @param retryBaseDelay     첫 실패 후 재시도 간격. 기본 1분
 * @param retryMaxDelay      재시도 간격 상한. 기본 1시간
 * @param retryJitterRatio   간격에 더할 무작위 비율의 최대값. 기본 0.2
 * @param retryMaxAttempts   자동 재시도 주기의 최대 선점 횟수. 넘으면 {@code DEAD}. 기본 10
 */
@ConfigurationProperties("lecture.hp-task")
public record LectureHpTaskProperties(Duration lease, Integer batchSize, Duration retryBaseDelay,
                                      Duration retryMaxDelay, Double retryJitterRatio, Integer retryMaxAttempts) {

    public LectureHpTaskProperties {
        lease = lease == null ? Duration.ofMinutes(5) : lease;
        batchSize = batchSize == null ? 100 : batchSize;
        retryBaseDelay = retryBaseDelay == null ? Duration.ofMinutes(1) : retryBaseDelay;
        retryMaxDelay = retryMaxDelay == null ? Duration.ofHours(1) : retryMaxDelay;
        retryJitterRatio = retryJitterRatio == null ? 0.2 : retryJitterRatio;
        retryMaxAttempts = retryMaxAttempts == null ? 10 : retryMaxAttempts;
    }
}
