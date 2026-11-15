package com.hapbang.auxiliary.global.ratelimit;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.internal.AtomicRateLimiter;
import lombok.extern.slf4j.Slf4j;

/**
 * 키(회원 ID 등)마다 Resilience4j {@code RateLimiter}로 요청 수를 센다 (지갑 #627 {@code UserRateLimiter}를 빌려 와 키를 문자열로 바꿈, #715).
 * <ul>
 *     <li>앱 메모리에서 센다. replica가 1개라 이 한도가 곧 전체 한도다. 배포 중 두 pod가 겹치는 동안은 한도가 잠깐 최대 2배가 된다(허용).</li>
 *     <li>기다리지 않는다(timeout 0). 넘으면 다시 시도할 수 있을 때까지 남은 초를 담아 {@link RateLimitExceededException}을 던진다.</li>
 *     <li>지갑과 다른 점: 키가 문자열이고, 키별 limiter를 크기·만료 상한이 있는 Caffeine 캐시에 둔다. 두 주기 동안 쓰지 않은 키는
 *     지우고, 키 수가 상한을 넘으면 오래 안 쓴 키부터 지운다. 지운 키는 다음 요청에서 새 limiter(가득 찬 한도)로 시작한다.</li>
 *     <li>{@link AtomicRateLimiter}는 Resilience4j의 {@code internal} 패키지 클래스다. 공개 {@code RateLimiter.Metrics}에는 다음 허용까지
 *     남은 시간이 없어 {@code Retry-After}를 정확히 계산하려고 쓴다(지갑과 같다). 버전은 루트 카탈로그(2.4.0)로 고정한다.</li>
 * </ul>
 * <ul>
 *     <li>거절은 요청마다 로그를 남기지 않는다(docs/dev/logging.md 429). 키가 거절되기 시작할 때 WARN, 다시 받기 시작할 때 INFO를 한 번씩 남긴다.</li>
 * </ul>
 * 모듈이 API마다 하나씩 만든다. 거절 횟수 지표는 모듈이 낸다(지표 이름에 모듈이 들어가게). 키는 지표 태그에 넣지 않는다.
 */
@Slf4j
public class KeyedRateLimiter {

    private final String name;
    private final RateLimiterConfig config;
    private final Cache<String, Entry> limiters;

    /**
     * @param name          limiter 이름(로그·진단용, 예: {@code chatbot-faq})
     * @param limitForPeriod 한 주기에 허용하는 요청 수
     * @param period         주기
     * @param maxKeys        메모리에 둘 키 수 상한
     */
    public KeyedRateLimiter(String name, int limitForPeriod, Duration period, long maxKeys) {
        this(name, limitForPeriod, period, maxKeys, Ticker.systemTicker());
    }

    /** 테스트가 캐시 만료 시각을 바꿀 때 쓴다 */
    KeyedRateLimiter(String name, int limitForPeriod, Duration period, long maxKeys, Ticker ticker) {
        this.name = name;
        this.config = RateLimiterConfig.custom()
                .limitForPeriod(limitForPeriod)
                .limitRefreshPeriod(period)
                .timeoutDuration(Duration.ZERO)
                .build();
        this.limiters = Caffeine.newBuilder()
                .maximumSize(maxKeys)
                .expireAfterAccess(period.multipliedBy(2))
                .ticker(ticker)
                .build();
    }

    /**
     * 요청 하나를 센다.
     *
     * @throws RateLimitExceededException 이 키가 한도를 넘었을 때
     */
    public void acquire(String key) {
        Entry entry = limiters.get(key, k -> new Entry(new AtomicRateLimiter(name + "-" + k, config), new AtomicBoolean()));
        if (entry.limiter().acquirePermission()) {
            if (entry.limited().compareAndSet(true, false)) {
                log.info("호출 제한이 풀려 다시 받는다 limiter={}, key={}", name, key);
            }
            return;
        }
        if (entry.limited().compareAndSet(false, true)) {
            log.warn("호출 제한에 닿아 거절하기 시작한다 limiter={}, key={}", name, key);
        }
        long nanosToWait = entry.limiter().getDetailedMetrics().getNanosToWait();
        throw new RateLimitExceededException(Math.max(1, ceilSeconds(nanosToWait)));
    }

    /** 지금 메모리에 있는 키 수 (테스트·진단용) */
    long size() {
        limiters.cleanUp();
        return limiters.estimatedSize();
    }

    // 키 하나의 limiter와 지금 거절 중인지(상태가 바뀔 때만 로그를 남기려고 둔다)
    private record Entry(AtomicRateLimiter limiter, AtomicBoolean limited) {
    }

    private static long ceilSeconds(long nanos) {
        return (nanos + TimeUnit.SECONDS.toNanos(1) - 1) / TimeUnit.SECONDS.toNanos(1);
    }
}
