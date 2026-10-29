package com.hapbang.lecture.domain;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * HP 작업 재시도 정책: 지수 백오프 + 지터 (D57 (6)·(7)). 출연 {@code HpTaskRetryPolicy}와 같은 계산이다.
 * <pre>
 * 다음 시도 간격 = min(기본 간격 × 2^(시도 횟수 - 1), 최대 간격) + 지터(간격 × 지터 비율 × 0~1)
 * 기본값: 1분 → 2분 → 4분 → … → 최대 1시간, 10번째 시도까지 끝나지 않으면 DEAD
 * </pre>
 *
 * @param baseDelay   첫 실패 후 간격
 * @param maxDelay    간격 상한
 * @param maxAttempts 자동 재시도 주기의 최대 선점 횟수
 * @param jitterRatio 간격에 더할 무작위 비율의 최대값 (0 ~ 1)
 */
public record LectureHpTaskRetryPolicy(Duration baseDelay, Duration maxDelay, int maxAttempts, double jitterRatio) {

    /** 2의 거듭제곱 상한 (간격 계산이 넘치지 않도록) */
    private static final int MAX_EXPONENT = 30;

    public LectureHpTaskRetryPolicy {
        if (baseDelay == null || baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("재시도 기본 간격은 0보다 길어야 합니다.");
        }
        if (maxDelay == null || maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("재시도 최대 간격은 기본 간격 이상이어야 합니다.");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("최대 시도 횟수는 1 이상이어야 합니다.");
        }
        if (jitterRatio < 0 || jitterRatio > 1) {
            throw new IllegalArgumentException("지터 비율은 0~1 사이여야 합니다.");
        }
    }

    /** 이번 시도까지 포함한 시도 횟수가 최대 횟수에 닿았는지 */
    public boolean isExhausted(int attemptCount) {
        return attemptCount >= maxAttempts;
    }

    /**
     * 다음 시도 시각.
     *
     * @param attemptCount 이번 실패까지 포함한 시도 횟수 (1 이상)
     * @param random       0 이상 1 미만의 난수 (테스트에서는 고정값을 넣는다)
     */
    public LocalDateTime nextAttemptAt(int attemptCount, LocalDateTime now, double random) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), MAX_EXPONENT);
        long delayMillis = Math.min(baseDelay.toMillis() * (1L << exponent), maxDelay.toMillis());
        long jitterMillis = (long) (delayMillis * jitterRatio * random);
        return now.plus(Duration.ofMillis(delayMillis + jitterMillis));
    }
}
