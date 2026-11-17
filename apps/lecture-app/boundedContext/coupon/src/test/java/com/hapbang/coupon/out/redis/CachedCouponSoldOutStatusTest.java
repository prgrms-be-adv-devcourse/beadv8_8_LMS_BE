package com.hapbang.coupon.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.hapbang.coupon.config.CouponIssueDeliveryProperties;
import com.hapbang.coupon.config.CouponSoldOutCacheProperties;
import com.hapbang.coupon.config.CouponTestProperties;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 매진 캐시가 TTL이 지나면 Redis를 다시 읽고, 읽지 못하면 TTL 동안 매진 아님으로 담고, TTL 0이면 끄고, 항목 수 상한과 만료 제거로 크기를 제한하고, hit·miss를 지표로 남긴다.
// 운영 생성자로 만들고 Redis 읽기(sold_out GET)만 mock으로 대신한다. 실제 Redis 읽기는 CachedCouponSoldOutStatusRedisTest가 본다.
// TTL 판정 자체는 SoldOutSignalTest가 본다. 크기는 운영이 등록한 Caffeine 지표(cache.size)로 읽는다. Caffeine 정리는 비동기라 기다린다
class CachedCouponSoldOutStatusTest {

    private static final long COUPON_ID = 42;

    private final StringRedisTemplate redisTemplate = mock();
    private final ValueOperations<String, String> values = mock();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T03:00:00Z"));
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(values);
    }

    @Test
    void TTL이_지나면_매진_표시를_다시_읽는다() {
        CachedCouponSoldOutStatus cache = cache(clock);
        when(values.get(soldOutKey(COUPON_ID))).thenReturn(null, "1");

        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        clock.advance(Duration.ofMillis(999));
        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(cache.isSoldOut(COUPON_ID)).isTrue();

        verify(values, times(2)).get(soldOutKey(COUPON_ID));
    }

    @Test
    void Lua가_매진을_알린_뒤_TTL_안에서는_Redis를_부르지_않고_매진이다() {
        CachedCouponSoldOutStatus cache = cache(clock);
        cache.markSoldOut(COUPON_ID);

        clock.advance(Duration.ofMillis(999));
        assertThat(cache.isSoldOut(COUPON_ID)).isTrue();
        verifyNoInteractions(values);
    }

    @Test
    void 매진_표시를_읽지_못하면_TTL_동안_매진이_아닌_것으로_담아_한_요청에서_다시_기다리지_않는다() {
        CachedCouponSoldOutStatus cache = cache(clock);
        when(values.get(soldOutKey(COUPON_ID)))
                .thenThrow(new RedisConnectionFailureException("redis"))
                .thenReturn("1");

        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        verify(values, times(1)).get(soldOutKey(COUPON_ID));

        clock.advance(CouponTestProperties.issueDelivery().soldOutCacheTtl().plusMillis(1));
        assertThat(cache.isSoldOut(COUPON_ID)).isTrue();
        verify(values, times(2)).get(soldOutKey(COUPON_ID));
    }

    @Test
    void Redis_템플릿_상태_오류도_읽지_못한_것으로_보고_매진이_아닌_것으로_담는다() {
        CachedCouponSoldOutStatus cache = cache(clock);
        when(values.get(soldOutKey(COUPON_ID))).thenThrow(new IllegalStateException("redis"));

        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        verify(values, times(1)).get(soldOutKey(COUPON_ID));
    }

    @Test
    void 시계가_읽은_시각보다_앞으로_돌아가면_다시_읽는다() {
        CachedCouponSoldOutStatus cache = cache(clock);
        when(values.get(soldOutKey(COUPON_ID))).thenReturn(null, "1");

        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        clock.advance(Duration.ofMillis(-1));
        assertThat(cache.isSoldOut(COUPON_ID)).isTrue();
        verify(values, times(2)).get(soldOutKey(COUPON_ID));
    }

    @Test
    void TTL이_0이면_매진_표시를_읽지_않고_Lua에_맡긴다() {
        CachedCouponSoldOutStatus cache = new CachedCouponSoldOutStatus(redisTemplate,
                new CouponIssueDeliveryProperties(null, null, Duration.ZERO, null, null),
                CouponTestProperties.soldOutCache(), clock, registry());
        cache.markSoldOut(COUPON_ID);

        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void 항목_수가_상한을_넘으면_덜_쓰는_항목을_내보내_상한_안으로_유지한다() {
        CachedCouponSoldOutStatus cache = cache(CouponTestProperties.issueDelivery(), 2, clock);

        for (long couponId = 1; couponId <= 5; couponId++) {
            cache.isSoldOut(couponId);
        }
        cache.markSoldOut(6);

        // 어느 쿠폰을 내보낼지는 Caffeine이 정한다. 크기가 상한 안이고 내보낸 일이 있는지만 본다
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(size()).isLessThanOrEqualTo(2);
            assertThat(meterRegistry.get("cache.evictions").tag("cache", CachedCouponSoldOutStatus.METRIC_CACHE_NAME)
                    .functionCounter().count()).isPositive();
        });
    }

    @Test
    void 상한을_채운_뒤_매진_신호를_담은_쿠폰은_TTL_안에서_Redis를_부르지_않고_매진이다() {
        CachedCouponSoldOutStatus cache = cache(CouponTestProperties.issueDelivery(), 2, clock);
        for (long couponId = 1; couponId <= 5; couponId++) {
            cache.isSoldOut(couponId);
        }

        cache.markSoldOut(6);

        assertThat(cache.isSoldOut(6)).isTrue();
        verify(values, never()).get(soldOutKey(6));
    }

    @Test
    void 지표는_TTL_안에서_재사용하면_hit_담긴_값이_없으면_miss로_센다() {
        CachedCouponSoldOutStatus cache = cache(clock);

        cache.isSoldOut(COUPON_ID);
        cache.isSoldOut(COUPON_ID);
        cache.isSoldOut(COUPON_ID);

        assertThat(gets("hit")).isEqualTo(2);
        assertThat(gets("miss")).isEqualTo(1);
    }

    @Test
    void TTL이_지난_뒤_항목을_넣으면_만료_항목을_지워_새_쿠폰을_담는다() {
        // Caffeine 만료는 단조 시계라 실제 시간으로 본다. TTL을 짧게 두고 쿠폰 시계도 실제 시계를 쓴다
        Duration ttl = Duration.ofMillis(100);
        CachedCouponSoldOutStatus cache = cache(new CouponIssueDeliveryProperties(null, null, ttl, null, null), 2,
                Clock.systemUTC());
        cache.isSoldOut(1);
        cache.isSoldOut(2);

        await().pollDelay(ttl.plusMillis(50)).atMost(Duration.ofSeconds(5)).until(() -> true);
        cache.isSoldOut(3);
        cache.isSoldOut(3);

        verify(values, times(1)).get(soldOutKey(3));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(size()).isEqualTo(1));
    }

    private CachedCouponSoldOutStatus cache(Clock clock) {
        return cache(CouponTestProperties.issueDelivery(), null, clock);
    }

    private CachedCouponSoldOutStatus cache(CouponIssueDeliveryProperties properties, Integer maxEntries, Clock clock) {
        return new CachedCouponSoldOutStatus(redisTemplate, properties, new CouponSoldOutCacheProperties(maxEntries),
                clock, registry());
    }

    private ObjectProvider<MeterRegistry> registry() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("meterRegistry", meterRegistry);
        return beanFactory.getBeanProvider(MeterRegistry.class);
    }

    private static String soldOutKey(long couponId) {
        return CouponRedisKeys.soldOut(couponId);
    }

    /** 운영이 등록한 Caffeine 지표의 항목 수(정리가 끝나야 줄어든다) */
    private double size() {
        return meterRegistry.get("cache.size").tag("cache", CachedCouponSoldOutStatus.METRIC_CACHE_NAME).gauge().value();
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
