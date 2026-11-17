package com.hapbang.coupon.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.hapbang.coupon.config.CouponIssueDeliveryProperties;
import com.hapbang.coupon.config.CouponSoldOutCacheProperties;

import io.micrometer.core.instrument.MeterRegistry;

// 운영 생성자가 실제 Redis의 매진 표시(sold_out)를 읽는다. TTL 0이면 Redis를 읽지 않으므로 기본 TTL(1초)로 만든다
class CachedCouponSoldOutStatusRedisTest extends CouponRedisTestSupport {

    private static final long EPOCH = 1;

    @Test
    @DisplayName("마지막 1장 접수 뒤의 쿠폰은 매진이고 재고가 남은 쿠폰은 매진이 아니다")
    void readsSoldOutMarker() {
        long soldOutCouponId = newCouponId();
        open(control(soldOutCouponId, EPOCH, 1));
        store.tryIssue(attempt(soldOutCouponId, 7, "req-1"));
        long openCouponId = newCouponId();
        open(control(openCouponId, EPOCH, 1));

        CachedCouponSoldOutStatus status = new CachedCouponSoldOutStatus(redisTemplate,
                CouponIssueDeliveryProperties.defaults(), CouponSoldOutCacheProperties.defaults(), Clock.systemUTC(),
                new DefaultListableBeanFactory().getBeanProvider(MeterRegistry.class));

        assertThat(status.isSoldOut(soldOutCouponId)).isTrue();
        assertThat(status.isSoldOut(openCouponId)).isFalse();
    }
}
