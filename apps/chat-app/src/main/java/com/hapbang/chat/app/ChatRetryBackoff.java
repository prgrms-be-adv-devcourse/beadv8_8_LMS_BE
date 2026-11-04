package com.hapbang.chat.app;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 재시도 다음 시각. 지수 증가를 상한으로 자르고 jitter로 흔들어 같은 시각에 몰리지 않게 한다.
 */
public final class ChatRetryBackoff {

    private ChatRetryBackoff() {
    }

    /**
     * @param attempt 지금까지 실패한 횟수(1부터)
     */
    public static LocalDateTime nextAttemptAt(ChatDeliveryProperties.Backoff backoff, int attempt, LocalDateTime now) {
        return now.plus(delay(backoff, attempt));
    }

    static Duration delay(ChatDeliveryProperties.Backoff backoff, int attempt) {
        long initial = backoff.initial().toMillis();
        long max = Math.max(initial, backoff.max().toMillis());
        int exponent = Math.min(Math.max(attempt, 1) - 1, 30);
        long base = Math.min(max, initial * (1L << exponent));
        double factor = 1 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * backoff.jitter();
        return Duration.ofMillis(Math.max(0, Math.round(base * factor)));
    }
}
