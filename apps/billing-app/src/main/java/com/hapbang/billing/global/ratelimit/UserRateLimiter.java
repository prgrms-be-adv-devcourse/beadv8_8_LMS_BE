package com.hapbang.billing.global.ratelimit;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.internal.AtomicRateLimiter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * 회원 × API마다 Resilience4j {@code RateLimiter}로 요청 수를 센다 (#627, BD-140).
 * <ul>
 *     <li>앱 메모리에서 센다. 앱마다 replica가 1개라 이 한도가 곧 전체 한도다(api-gateway.md "rate limit"). 배포 중 두 pod가 겹치는
 *     동안은 한도가 잠깐 최대 2배가 된다(허용).</li>
 *     <li>기다리지 않는다(timeout 0). 넘으면 다시 시도할 수 있을 때까지 남은 초를 담아 {@link RateLimitExceededException}을 던진다.</li>
 *     <li>회원 수만큼 카운터가 쌓이지 않게, 한 주기마다 두 주기 넘게 쓰지 않은 카운터를 지운다. 사용 시각 갱신({@code compute})과
 *     삭제({@code computeIfPresent})는 같은 키에서 직렬화되므로, 지금 쓰는 카운터를 지워 그 요청이 맵 밖의 카운터로 통과하는 일은 없다
 *     (#627 리뷰).</li>
 *     <li>{@link AtomicRateLimiter}는 Resilience4j의 {@code internal} 패키지 클래스다. 공개 {@code RateLimiter.Metrics}에는 다음 허용까지
 *     남은 시간이 없어 {@code Retry-After}를 정확히 계산하려고 쓴다. 버전은 카탈로그(2.4.0)로 고정하고, 올릴 때 깨지면 컴파일과
 *     {@code UserRateLimiterTest}의 Retry-After 범위 확인이 잡는다.</li>
 * </ul>
 * 거절 횟수는 {@code hapbang.billing.rate.limit.rejected{api}}로 낸다. 회원 ID는 태그에 넣지 않는다(지표 수가 회원 수만큼 늘지 않게).
 */
@Component
public class UserRateLimiter implements MeterBinder {

    static final String REJECTED_METRIC = "hapbang.billing.rate.limit.rejected";

    private final Duration period;
    private final LongSupplier nanoTime;
    private final Map<RateLimitedApi.Group, RateLimiterConfig> configs = new EnumMap<>(RateLimitedApi.Group.class);
    private final ConcurrentMap<Key, Entry> limiters = new ConcurrentHashMap<>();
    private final Map<RateLimitedApi, LongAdder> rejected = new EnumMap<>(RateLimitedApi.class);
    private final AtomicLong lastSweepNanos;

    @Autowired
    UserRateLimiter(RateLimitProperties properties) {
        this(properties, Duration.ofMinutes(1), System::nanoTime);
    }

    /** 테스트가 주기와 정리 시각을 바꿀 때 쓴다. 운영 주기는 1분이다 */
    UserRateLimiter(RateLimitProperties properties, Duration period, LongSupplier nanoTime) {
        this.period = period;
        this.nanoTime = nanoTime;
        for (RateLimitedApi.Group group : RateLimitedApi.Group.values()) {
            configs.put(group, RateLimiterConfig.custom()
                    .limitForPeriod(properties.limitOf(group))
                    .limitRefreshPeriod(period)
                    .timeoutDuration(Duration.ZERO)
                    .build());
        }
        for (RateLimitedApi api : RateLimitedApi.values()) {
            rejected.put(api, new LongAdder());
        }
        this.lastSweepNanos = new AtomicLong(nanoTime.getAsLong());
    }

    /**
     * 요청 하나를 센다.
     *
     * @throws RateLimitExceededException 이 회원이 이 API의 한도를 넘었을 때
     */
    public void acquire(long userId, RateLimitedApi api) {
        long now = nanoTime.getAsLong();
        sweepIfDue(now);
        // 만들기와 사용 시각 갱신을 한 번에 한다. sweep의 삭제와 같은 키에서 직렬화된다
        Entry entry = limiters.compute(new Key(userId, api), (key, current) -> {
            Entry used = current != null ? current
                    : new Entry(new AtomicRateLimiter(key.name(), configs.get(api.group())));
            used.lastUsedNanos.set(now);
            return used;
        });
        if (entry.limiter.acquirePermission()) {
            return;
        }
        rejected.get(api).increment();
        long nanosToWait = entry.limiter.getDetailedMetrics().getNanosToWait();
        throw new RateLimitExceededException(Math.max(1, ceilSeconds(nanosToWait)));
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (RateLimitedApi api : RateLimitedApi.values()) {
            FunctionCounter.builder(REJECTED_METRIC, rejected.get(api), LongAdder::doubleValue)
                    .description("회원별 요청 한도를 넘어 429로 거절한 횟수")
                    .tag("api", api.tag())
                    .register(registry);
        }
    }

    /** 지금 메모리에 있는 회원 × API 카운터 수 (테스트·진단용) */
    int size() {
        return limiters.size();
    }

    /** 이 회원 × API의 카운터가 메모리에 있는지 (테스트용) */
    boolean contains(long userId, RateLimitedApi api) {
        return limiters.containsKey(new Key(userId, api));
    }

    // 한 주기에 한 번, 두 주기 넘게 쓰지 않은 카운터를 지운다. 지운 회원은 다음 요청에서 새 카운터(가득 찬 한도)로 시작한다.
    private void sweepIfDue(long now) {
        long last = lastSweepNanos.get();
        if (now - last < period.toNanos() || !lastSweepNanos.compareAndSet(last, now)) {
            return;
        }
        long idleBefore = now - period.toNanos() * 2;
        // 키마다 잠근 상태에서 다시 확인하고 지운다. 그 사이 acquire가 사용 시각을 갱신했으면 남긴다
        for (Key key : limiters.keySet()) {
            limiters.computeIfPresent(key, (k, entry) -> entry.lastUsedNanos.get() < idleBefore ? null : entry);
        }
    }

    private static long ceilSeconds(long nanos) {
        return (nanos + TimeUnit.SECONDS.toNanos(1) - 1) / TimeUnit.SECONDS.toNanos(1);
    }

    private record Key(long userId, RateLimitedApi api) {
        String name() {
            return "billing-" + api.tag() + "-" + userId;
        }
    }

    private static final class Entry {
        private final AtomicRateLimiter limiter;
        private final AtomicLong lastUsedNanos = new AtomicLong();

        Entry(AtomicRateLimiter limiter) {
            this.limiter = limiter;
        }
    }
}
