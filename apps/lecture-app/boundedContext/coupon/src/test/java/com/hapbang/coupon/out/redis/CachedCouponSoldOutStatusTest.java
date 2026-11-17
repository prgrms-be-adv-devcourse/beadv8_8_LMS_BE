package com.hapbang.coupon.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.LongPredicate;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import com.hapbang.coupon.config.CouponIssueDeliveryProperties;
import com.hapbang.coupon.config.CouponSoldOutCacheProperties;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 매진 캐시가 TTL이 지나면 Redis를 다시 읽고, 읽지 못하면 TTL 동안 매진 아님으로 담고, TTL 0이면 끄고, 항목 수 상한과 만료 제거로 크기를 제한하고, hit·miss를 지표로 남긴다.
// TTL 판정 자체는 SoldOutSignalTest가 본다
class CachedCouponSoldOutStatusTest {

    private static final long COUPON_ID = 42;

    /** Redis 매진 표시 읽기. 실제 Redis 읽기는 CachedCouponSoldOutStatusRedisTest가 본다 */
    private final LongPredicate redisSoldOut = mock();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T03:00:00Z"));
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void TTL이_지나면_매진_표시를_다시_읽는다() {
        CachedCouponSoldOutStatus cache = cache(CouponIssueDeliveryProperties.defaults(), CouponSoldOutCacheProperties.defaults());
        when(redisSoldOut.test(COUPON_ID)).thenReturn(false, true);

        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        clock.advance(Duration.ofMillis(999));
        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(cache.isSoldOut(COUPON_ID)).isTrue();

        verify(redisSoldOut, times(2)).test(COUPON_ID);
    }

    @Test
    void Lua가_매진을_알린_뒤_TTL_안에서는_Redis를_부르지_않고_매진이다() {
        CachedCouponSoldOutStatus cache = cache(CouponIssueDeliveryProperties.defaults(), CouponSoldOutCacheProperties.defaults());
        cache.markSoldOut(COUPON_ID);

        clock.advance(Duration.ofMillis(999));
        assertThat(cache.isSoldOut(COUPON_ID)).isTrue();
        verifyNoInteractions(redisSoldOut);
    }

    @Test
    void 매진_표시를_읽지_못하면_TTL_동안_매진이_아닌_것으로_담아_한_요청에서_다시_기다리지_않는다() {
        CachedCouponSoldOutStatus cache = cache(CouponIssueDeliveryProperties.defaults(), CouponSoldOutCacheProperties.defaults());
        when(redisSoldOut.test(COUPON_ID))
                .thenThrow(new RedisConnectionFailureException("redis"))
                .thenReturn(true);

        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        verify(redisSoldOut, times(1)).test(COUPON_ID);

        clock.advance(CouponIssueDeliveryProperties.defaults().soldOutCacheTtl().plusMillis(1));
        assertThat(cache.isSoldOut(COUPON_ID)).isTrue();
        verify(redisSoldOut, times(2)).test(COUPON_ID);
    }

    @Test
    void Redis_템플릿_상태_오류도_읽지_못한_것으로_보고_매진이_아닌_것으로_담는다() {
        CachedCouponSoldOutStatus cache = cache(CouponIssueDeliveryProperties.defaults(), CouponSoldOutCacheProperties.defaults());
        when(redisSoldOut.test(COUPON_ID)).thenThrow(new IllegalStateException("redis"));

        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        verify(redisSoldOut, times(1)).test(COUPON_ID);
    }

    @Test
    void 시계가_읽은_시각보다_앞으로_돌아가면_다시_읽는다() {
        CachedCouponSoldOutStatus cache = cache(CouponIssueDeliveryProperties.defaults(), CouponSoldOutCacheProperties.defaults());
        when(redisSoldOut.test(COUPON_ID)).thenReturn(false, true);

        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        clock.advance(Duration.ofMillis(-1));
        assertThat(cache.isSoldOut(COUPON_ID)).isTrue();
        verify(redisSoldOut, times(2)).test(COUPON_ID);
    }

    @Test
    void TTL이_0이면_매진_표시를_읽지_않고_Lua에_맡긴다() {
        CachedCouponSoldOutStatus cache = cache(new CouponIssueDeliveryProperties(null, null, Duration.ZERO, null),
                CouponSoldOutCacheProperties.defaults());
        cache.markSoldOut(COUPON_ID);

        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        verifyNoInteractions(redisSoldOut);
    }

    @Test
    void 항목_수가_상한을_넘으면_덜_쓰는_항목을_내보내_상한_안으로_유지한다() {
        CachedCouponSoldOutStatus cache = cacheWithMaxEntries(2);
        when(redisSoldOut.test(anyLong())).thenReturn(false);

        for (long couponId = 1; couponId <= 5; couponId++) {
            cache.isSoldOut(couponId);
        }
        cache.markSoldOut(6);

        // 어느 쿠폰을 내보낼지는 Caffeine이 정한다. 크기가 상한 안이고 내보낸 일이 있는지만 본다
        assertThat(cache.size()).isLessThanOrEqualTo(2);
        assertThat(meterRegistry.get("cache.evictions").tag("cache", CachedCouponSoldOutStatus.METRIC_CACHE_NAME)
                .functionCounter().count()).isPositive();
    }

    @Test
    void 상한을_채운_뒤_매진_신호를_담은_쿠폰은_TTL_안에서_Redis를_부르지_않고_매진이다() {
        CachedCouponSoldOutStatus cache = cacheWithMaxEntries(2);
        when(redisSoldOut.test(anyLong())).thenReturn(false);
        for (long couponId = 1; couponId <= 5; couponId++) {
            cache.isSoldOut(couponId);
        }

        cache.markSoldOut(6);

        assertThat(cache.isSoldOut(6)).isTrue();
        verify(redisSoldOut, never()).test(6);
    }

    @Test
    void 지표는_TTL_안에서_재사용하면_hit_담긴_값이_없으면_miss로_센다() {
        CachedCouponSoldOutStatus cache = cache(CouponIssueDeliveryProperties.defaults(), CouponSoldOutCacheProperties.defaults());
        when(redisSoldOut.test(COUPON_ID)).thenReturn(false);

        cache.isSoldOut(COUPON_ID);
        cache.isSoldOut(COUPON_ID);
        cache.isSoldOut(COUPON_ID);

        assertThat(gets("hit")).isEqualTo(2);
        assertThat(gets("miss")).isEqualTo(1);
    }

    @Test
    void TTL이_지난_뒤_항목을_넣으면_만료_항목을_지워_새_쿠폰을_담는다() {
        CachedCouponSoldOutStatus cache = cacheWithMaxEntries(2);
        when(redisSoldOut.test(anyLong())).thenReturn(false);
        cache.isSoldOut(1);
        cache.isSoldOut(2);

        clock.advance(Duration.ofSeconds(1));
        cache.isSoldOut(3);

        assertThat(cache.size()).isEqualTo(1);
        cache.isSoldOut(3);
        verify(redisSoldOut, times(1)).test(3);
    }

    private CachedCouponSoldOutStatus cacheWithMaxEntries(int maxEntries) {
        return cache(CouponIssueDeliveryProperties.defaults(), new CouponSoldOutCacheProperties(maxEntries));
    }

    /** 만료 시계를 테스트 시계와 함께 움직이고 Caffeine 정리 작업을 호출한 스레드에서 바로 돌려 시간·크기 판정을 결정적으로 만든다 */
    private CachedCouponSoldOutStatus cache(CouponIssueDeliveryProperties properties, CouponSoldOutCacheProperties cacheProperties) {
        return new CachedCouponSoldOutStatus(redisSoldOut, properties, cacheProperties, clock, meterRegistry, clock::nanos,
                Runnable::run);
    }

    private double gets(String result) {
        return meterRegistry.get("cache.gets").tag("cache", CachedCouponSoldOutStatus.METRIC_CACHE_NAME).tag("result", result)
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
