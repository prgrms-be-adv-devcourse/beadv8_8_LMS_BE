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

// 매진 캐시가 TTL이 지나면 Redis를 다시 읽고, TTL 0이면 끄고, 항목 수 상한과 만료 제거로 크기를 제한한다. TTL 판정 자체는 SoldOutSignalTest가 본다
class CouponSoldOutCacheTest {

    private static final long COUPON_ID = 42;

    private final CouponIssueStore issueStore = mock();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T03:00:00Z"));

    @Test
    void TTL이_지나면_매진_표시를_다시_읽는다() {
        CouponSoldOutCache cache = new CouponSoldOutCache(issueStore, CouponIssueDeliveryProperties.defaults(),
                CouponSoldOutCacheProperties.defaults(), clock);
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
        CouponSoldOutCache cache = new CouponSoldOutCache(issueStore, CouponIssueDeliveryProperties.defaults(),
                CouponSoldOutCacheProperties.defaults(), clock);
        cache.markSoldOut(COUPON_ID);

        clock.advance(Duration.ofMillis(999));
        assertThat(cache.isSoldOut(COUPON_ID)).isTrue();
        verifyNoInteractions(issueStore);
    }

    @Test
    void 매진_표시를_읽지_못하면_매진이_아닌_것으로_보고_담지_않아_다음에_다시_읽는다() {
        CouponSoldOutCache cache = new CouponSoldOutCache(issueStore, CouponIssueDeliveryProperties.defaults(),
                CouponSoldOutCacheProperties.defaults(), clock);
        when(issueStore.isSoldOut(COUPON_ID))
                .thenThrow(new CouponIssueStoreUnavailableException("redis", null))
                .thenReturn(true);

        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        assertThat(cache.isSoldOut(COUPON_ID)).isTrue();
        verify(issueStore, times(2)).isSoldOut(COUPON_ID);
    }

    @Test
    void 시계가_읽은_시각보다_앞으로_돌아가면_다시_읽는다() {
        CouponSoldOutCache cache = new CouponSoldOutCache(issueStore, CouponIssueDeliveryProperties.defaults(),
                CouponSoldOutCacheProperties.defaults(), clock);
        when(issueStore.isSoldOut(COUPON_ID)).thenReturn(false, true);

        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        clock.advance(Duration.ofMillis(-1));
        assertThat(cache.isSoldOut(COUPON_ID)).isTrue();
        verify(issueStore, times(2)).isSoldOut(COUPON_ID);
    }

    @Test
    void TTL이_0이면_매진_표시를_읽지_않고_Lua에_맡긴다() {
        CouponSoldOutCache cache = new CouponSoldOutCache(issueStore,
                new CouponIssueDeliveryProperties(null, null, Duration.ZERO, null), CouponSoldOutCacheProperties.defaults(), clock);
        cache.markSoldOut(COUPON_ID);

        assertThat(cache.isSoldOut(COUPON_ID)).isFalse();
        verifyNoInteractions(issueStore);
    }

    @Test
    void 항목_수가_상한에_닿으면_새_쿠폰은_담지_않고_매번_다시_읽는다() {
        CouponSoldOutCache cache = cacheWithMaxEntries(2);
        when(issueStore.isSoldOut(anyLong())).thenReturn(false);

        cache.isSoldOut(1);
        cache.isSoldOut(2);
        cache.isSoldOut(3);
        cache.isSoldOut(3);
        cache.markSoldOut(4);

        assertThat(cache.size()).isEqualTo(2);
        verify(issueStore, times(2)).isSoldOut(3);
        // 이미 담긴 쿠폰은 상한이어도 갱신한다
        cache.markSoldOut(1);
        assertThat(cache.isSoldOut(1)).isTrue();
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
        return new CouponSoldOutCache(issueStore, CouponIssueDeliveryProperties.defaults(),
                new CouponSoldOutCacheProperties(maxEntries), clock);
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
