package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.config.CouponIssueDeliveryProperties;
import com.hapbang.coupon.config.CouponSoldOutCacheProperties;
import com.hapbang.coupon.domain.SoldOutSignal;

import lombok.extern.slf4j.Slf4j;

/**
 * 매진 표시 메모리 캐시. 쿠폰마다 Redis sold_out을 읽은 결과를 {@code hapbang.coupon.sold-out-cache-ttl} 동안 재사용해, 매진 뒤 몰리는
 * 접수를 Redis 호출 없이 거절한다. 확인 자체도 Redis GET이라 TTL이 지나면 다시 읽는다. 최종 판정은 발급 Lua다: 매진이 아니라는 값은 Lua를
 * 막지 않고, Redis에 닿지 못하면 매진이 아닌 것으로 보고 Lua에 맡긴다. TTL이 0이면 캐시를 끄고 sold_out을 읽지 않는다.
 * <p>
 * 인스턴스(JVM)마다 따로 가진다. 매진이 아니라는 값도 담는다(발급 중인 쿠폰마다 요청당 GET이 하나 더 늘지 않게). 접수 경로는 쿠폰이 있는지
 * 확인하기 전에 이 캐시를 보므로 없는 쿠폰 ID도 항목이 될 수 있어 크기를 제한한다.
 * <ul>
 *     <li>만료 제거: 항목을 넣을 때 앞 정리에서 TTL 이상 지났으면 만료 항목을 모두 지운다(TTL마다 한 스레드만 한다).</li>
 *     <li>상한: 항목 수가 {@code hapbang.coupon.sold-out-cache-max-entries}에 닿으면 새 쿠폰은 담지 않는다(이미 있는 쿠폰은 갱신한다).
 *     담지 못한 쿠폰은 다음 확인 때 Redis를 다시 읽을 뿐이고 판정은 같다. 동시에 넣으면 상한을 조금 넘을 수 있다.</li>
 * </ul>
 */
@Slf4j
@Component
public class CouponSoldOutCache {

    private final CouponIssueStore couponIssueStore;
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;
    private final Map<Long, SoldOutSignal> signals = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastSweepAt;

    public CouponSoldOutCache(CouponIssueStore couponIssueStore, CouponIssueDeliveryProperties properties,
                              CouponSoldOutCacheProperties cacheProperties,
                              @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponIssueStore = couponIssueStore;
        this.ttl = properties.soldOutCacheTtl();
        this.maxEntries = cacheProperties.soldOutCacheMaxEntries();
        this.clock = clock;
        this.lastSweepAt = new AtomicReference<>(clock.instant());
    }

    /** 매진으로 알고 있는가. TTL 안의 값이면 Redis를 부르지 않는다 */
    public boolean isSoldOut(long couponId) {
        if (ttl.isZero()) {
            return false;
        }
        SoldOutSignal cached = signals.get(couponId);
        var now = clock.instant();
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
        remember(couponId, new SoldOutSignal(soldOut, now));
        return soldOut;
    }

    /** 발급 Lua가 매진(0)을 돌려줬다. 다음 요청부터 TTL 동안 Redis를 부르지 않는다 */
    public void markSoldOut(long couponId) {
        if (!ttl.isZero()) {
            remember(couponId, new SoldOutSignal(true, clock.instant()));
        }
    }

    /** 지금 담고 있는 항목 수(만료됐지만 아직 지우지 않은 항목 포함) */
    int size() {
        return signals.size();
    }

    private void remember(long couponId, SoldOutSignal signal) {
        sweepExpired(signal.readAt());
        if (signals.size() >= maxEntries && !signals.containsKey(couponId)) {
            return;
        }
        signals.put(couponId, signal);
    }

    /** 앞 정리에서 TTL 이상 지났으면(시계가 뒤로 갔어도) 만료 항목을 지운다. 먼저 온 스레드 하나만 한다 */
    private void sweepExpired(Instant now) {
        Instant last = lastSweepAt.get();
        if (now.isBefore(last.plus(ttl)) && !now.isBefore(last)) {
            return;
        }
        if (lastSweepAt.compareAndSet(last, now)) {
            signals.values().removeIf(signal -> !signal.freshAt(now, ttl));
        }
    }
}
