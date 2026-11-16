package com.hapbang.coupon.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.config.CouponIssueDeliveryProperties;
import com.hapbang.coupon.config.CouponSoldOutCacheProperties;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 매진 캐시가 TTL이 지나면 Redis를 다시 읽고, TTL 0이면 끄고, 항목 수 상한과 만료 제거로 크기를 제한하고, hit·miss를 지표로 남긴다.
// TTL 판정 자체는 SoldOutSignalTest가 본다
class CouponSoldOutCacheTest {

    private static final long COUPON_ID = 42;

    private final CouponIssueStore issueStore = mock();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T03:00:00Z"));
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void TTL이_지나면_매진_표시를_다시_읽는다() {
        CouponSoldOutCache cache = cache(CouponIssueDeliveryProperties.defaults(), CouponSoldOutCacheProperties.defaults());
        when(issueStore.isSoldOut(COUPON_ID)).thenReturn(false, true);

        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        clock.advance(Duration.ofMillis(999));
        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(cache.isSoldOut(COUPON_ID)).isTrue();

        verify(issueStore, times(2)).isSoldOut(COUPON_ID);
    }

    @Test
    void Lua가_매진을_알린_뒤_TTL_안에서는_Redis를_부르지_않고_매진이다() {
        CouponSoldOutCache cache = cache(CouponIssueDeliveryProperties.defaults(), CouponSoldOutCacheProperties.defaults());
        cache.markSoldOut(COUPON_ID);

        clock.advance(Duration.ofMillis(999));
        assertThat(cache.isSoldOut(COUPON_ID)).isTrue();
        verifyNoInteractions(issueStore);
    }

    @Test
    void 매진_표시를_읽지_못하면_TTL_동안_매진이_아닌_것으로_담아_한_요청에서_다시_기다리지_않는다() {
        CouponSoldOutCache cache = cache(CouponIssueDeliveryProperties.defaults(), CouponSoldOutCacheProperties.defaults());
        when(issueStore.isSoldOut(COUPON_ID))
                .thenThrow(new CouponIssueStoreUnavailableException("redis", null))
                .thenReturn(true);

        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        verify(issueStore, times(1)).isSoldOut(COUPON_ID);

        clock.advance(CouponIssueDeliveryProperties.defaults().soldOutCacheTtl().plusMillis(1));
        assertThat(cache.isSoldOut(COUPON_ID)).isTrue();
        verify(issueStore, times(2)).isSoldOut(COUPON_ID);
    }

    @Test
    void 시계가_읽은_시각보다_앞으로_돌아가면_다시_읽는다() {
        CouponSoldOutCache cache = cache(CouponIssueDeliveryProperties.defaults(), CouponSoldOutCacheProperties.defaults());
        when(issueStore.isSoldOut(COUPON_ID)).thenReturn(false, true);

        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        clock.advance(Duration.ofMillis(-1));
        assertThat(cache.isSoldOut(COUPON_ID)).isTrue();
        verify(issueStore, times(2)).isSoldOut(COUPON_ID);
    }

    @Test
    void TTL이_0이면_매진_표시를_읽지_않고_Lua에_맡긴다() {
        CouponSoldOutCache cache = cache(new CouponIssueDeliveryProperties(null, null, Duration.ZERO, null),
                CouponSoldOutCacheProperties.defaults());
        cache.markSoldOut(COUPON_ID);

        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        verifyNoInteractions(issueStore);
    }

    @Test
    void 항목_수가_상한을_넘으면_덜_쓰는_항목을_내보내_상한_안으로_유지한다() {
        CouponSoldOutCache cache = cacheWithMaxEntries(2);
        when(issueStore.isSoldOut(anyLong())).thenReturn(false);

        for (long couponId = 1; couponId <= 5; couponId++) {
            cache.isSoldOut(couponId);
        }
        cache.markSoldOut(6);

        // 어느 쿠폰을 내보낼지는 Caffeine이 정한다. 크기가 상한 안이고 내보낸 일이 있는지만 본다
        assertThat(cache.size()).isLessThanOrEqualTo(2);
        assertThat(meterRegistry.get("cache.evictions").tag("cache", CouponSoldOutCache.METRIC_CACHE_NAME)
                .functionCounter().count()).isPositive();
    }

    @Test
    void 상한을_채운_뒤_매진_신호를_담은_쿠폰은_TTL_안에서_Redis를_부르지_않고_매진이다() {
        CouponSoldOutCache cache = cacheWithMaxEntries(2);
        when(issueStore.isSoldOut(anyLong())).thenReturn(false);
        for (long couponId = 1; couponId <= 5; couponId++) {
            cache.isSoldOut(couponId);
        }

        cache.markSoldOut(6);

        assertThat(cache.isSoldOut(6)).isTrue();
        verify(issueStore, never()).isSoldOut(6);
    }

    @Test
    void 지표는_TTL_안에서_재사용하면_hit_담긴_값이_없으면_miss로_센다() {
        CouponSoldOutCache cache = cache(CouponIssueDeliveryProperties.defaults(), CouponSoldOutCacheProperties.defaults());
        when(issueStore.isSoldOut(COUPON_ID)).thenReturn(false);

        cache.isSoldOut(COUPON_ID);
        cache.isSoldOut(COUPON_ID);
        cache.isSoldOut(COUPON_ID);

        assertThat(gets("hit")).isEqualTo(2);
        assertThat(gets("miss")).isEqualTo(1);
    }

    @Test
    void TTL이_지난_뒤_항목을_넣으면_만료_항목을_지워_새_쿠폰을_담는다() {
        CouponSoldOutCache cache = cacheWithMaxEntries(2);
        when(issueStore.isSoldOut(anyLong())).thenReturn(false);
        cache.isSoldOut(1);
        cache.isSoldOut(2);

        clock.advance(Duration.ofSeconds(1));
        cache.isSoldOut(3);

        assertThat(cache.size()).isEqualTo(1);
        cache.isSoldOut(3);
        verify(issueStore, times(1)).isSoldOut(3);
    }

    private CouponSoldOutCache cacheWithMaxEntries(int maxEntries) {
        return cache(CouponIssueDeliveryProperties.defaults(), new CouponSoldOutCacheProperties(maxEntries));
    }

    /** 만료 시계를 테스트 시계와 함께 움직이고 Caffeine 정리 작업을 호출한 스레드에서 바로 돌려 시간·크기 판정을 결정적으로 만든다 */
    private CouponSoldOutCache cache(CouponIssueDeliveryProperties properties, CouponSoldOutCacheProperties cacheProperties) {
        return new CouponSoldOutCache(issueStore, properties, cacheProperties, clock, meterRegistry, clock::nanos,
                Runnable::run);
    }

    private double gets(String result) {
        return meterRegistry.get("cache.gets").tag("cache", CouponSoldOutCache.METRIC_CACHE_NAME).tag("result", result)
                .functionCounter().count();
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        /** Caffeine 만료 시계로 쓰는 나노초. 이 시계와 함께 움직인다 */
        long nanos() {
            return now.getEpochSecond() * 1_000_000_000L + now.getNano();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
