package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.config.CouponIssueDeliveryProperties;
import com.hapbang.coupon.config.CouponSoldOutCacheProperties;
import com.hapbang.coupon.domain.SoldOutSignal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 매진 표시 메모리 캐시. 쿠폰마다 Redis sold_out을 읽은 결과를 {@code hapbang.coupon.sold-out-cache-ttl} 동안 재사용해, 매진 뒤 몰리는
 * 접수를 Redis 호출 없이 거절한다. 확인 자체도 Redis GET이라 TTL이 지나면 다시 읽는다. 최종 판정은 발급 Lua다: 매진이 아니라는 값은 Lua를
 * 막지 않고, Redis에 닿지 못하면 매진이 아닌 것으로 보고 Lua에 맡긴다. TTL이 0이면 캐시를 끄고 sold_out을 읽지 않는다.
 * <p>
 * 인스턴스(JVM)마다 따로 가진다. 매진이 아니라는 값도 담는다(발급 중인 쿠폰마다 요청당 GET이 하나 더 늘지 않게). 접수 경로는 쿠폰이 있는지
 * 확인하기 전에 이 캐시를 보므로 없는 쿠폰 ID도 항목이 될 수 있어 크기를 제한한다. TTL 만료와 크기 제한은 Caffeine 캐시 하나가 맡는다.
 * <ul>
 *     <li>만료: 쓴 뒤 TTL이 지나면 만료다(쿠폰 시계 기준). 다시 쓰면 그때부터 다시 센다. 시계가 읽은 시각보다 앞으로 돌아갔으면
 *     {@link SoldOutSignal#freshAt}이 다시 읽게 한다.</li>
 *     <li>상한: 항목 수가 {@code hapbang.coupon.sold-out-cache-max-entries}를 넘으면 Caffeine이 덜 쓰이는 항목을 내보낸다(방금 넣은
 *     항목일 수도 있다). 내보낸 쿠폰은 다음 확인 때 Redis를 다시 읽을 뿐이고 판정은 같다. 정리는 비동기라 잠깐 상한을 넘을 수 있다.</li>
 * </ul>
 * 지표는 Micrometer {@code cache.*}에 {@code cache=coupon.issue.sold_out} 태그로 남는다. TTL이 0이면 캐시가 없어 지표도 없다.
 */
@Slf4j
@Component
public class CouponSoldOutCache {

    /** Micrometer 캐시 지표의 {@code cache} 태그 값 */
    static final String METRIC_CACHE_NAME = "coupon.issue.sold_out";

    private final CouponIssueStore couponIssueStore;
    private final Duration ttl;
    private final Clock clock;
    /** TTL이 0이면 null이다(캐시를 쓰지 않는다) */
    private final Cache<Long, SoldOutSignal> signals;

    @Autowired
    public CouponSoldOutCache(CouponIssueStore couponIssueStore, CouponIssueDeliveryProperties properties,
                              CouponSoldOutCacheProperties cacheProperties,
                              @Qualifier(CouponClockConfig.CLOCK) Clock clock,
                              ObjectProvider<MeterRegistry> meterRegistry) {
        this(couponIssueStore, properties, cacheProperties, clock, meterRegistry.getIfAvailable(SimpleMeterRegistry::new),
                ForkJoinPool.commonPool());
    }

    /** 테스트가 Caffeine 정리 작업을 호출한 스레드에서 바로 돌리도록 실행기를 받는다 */
    CouponSoldOutCache(CouponIssueStore couponIssueStore, CouponIssueDeliveryProperties properties,
                       CouponSoldOutCacheProperties cacheProperties, Clock clock, MeterRegistry meterRegistry,
                       Executor maintenanceExecutor) {
        this.couponIssueStore = couponIssueStore;
        this.ttl = properties.soldOutCacheTtl();
        this.clock = clock;
        if (ttl.isZero()) {
            this.signals = null;
            return;
        }
        this.signals = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(cacheProperties.soldOutCacheMaxEntries())
                .ticker(tickerOf(clock))
                .executor(maintenanceExecutor)
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meterRegistry, signals, METRIC_CACHE_NAME);
    }

    /** 매진으로 알고 있는가. TTL 안의 값이면 Redis를 부르지 않는다 */
    public boolean isSoldOut(long couponId) {
        if (signals == null) {
            return false;
        }
        var now = clock.instant();
        SoldOutSignal cached = signals.getIfPresent(couponId);
        if (cached != null && cached.freshAt(now, ttl)) {
            return cached.soldOut();
        }
        boolean soldOut;
        try {
            soldOut = couponIssueStore.isSoldOut(couponId);
        } catch (CouponIssueStoreUnavailableException e) {
            log.debug("매진 표시를 읽지 못해 발급 Lua에 판정을 맡긴다 couponId={}", couponId, e);
            return false;
        }
        signals.put(couponId, new SoldOutSignal(soldOut, now));
        return soldOut;
    }

    /** 발급 Lua가 매진(0)을 돌려줬다. 다음 요청부터 TTL 동안 Redis를 부르지 않는다 */
    public void markSoldOut(long couponId) {
        if (signals != null) {
            signals.put(couponId, new SoldOutSignal(true, clock.instant()));
        }
    }

    /** 지금 담고 있는 항목 수. 만료·상한 정리를 먼저 끝낸 뒤 센다 */
    long size() {
        if (signals == null) {
            return 0;
        }
        signals.cleanUp();
        return signals.estimatedSize();
    }

    /** 쿠폰 시계를 Caffeine 시간(나노초)으로 바꾼다. 만료 판정이 {@link SoldOutSignal}과 같은 시계를 보게 한다 */
    private static Ticker tickerOf(Clock clock) {
        return () -> {
            Instant now = clock.instant();
            return now.getEpochSecond() * 1_000_000_000L + now.getNano();
        };
    }
}
