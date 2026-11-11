package com.hapbang.coupon.app;

import static com.hapbang.coupon.domain.CouponFixture.*;

import java.time.Clock;
import java.time.ZoneOffset;

import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.coupon.domain.Coupon;

/** app 테스트 값. 저장한 것처럼 ID를 붙인 쿠폰과 고정 시계 */
final class CouponAppFixture {

    static final long COUPON_ID = 1L;
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private CouponAppFixture() {
    }

    static Coupon withId(Coupon coupon) {
        ReflectionTestUtils.setField(coupon, "id", COUPON_ID);
        return coupon;
    }

    /** 특정 강의(10,000 HP) 10% 100장 플랫폼 쿠폰(DRAFT). 예산 100,000 HP */
    static Coupon draftPlatformCoupon() {
        return withId(platformCoupon());
    }

    /** 심사 중(REVIEW)인 판매자 쿠폰 */
    static Coupon reviewSellerCoupon() {
        Coupon coupon = withId(sellerCoupon());
        coupon.submitReview(NOW);
        return coupon;
    }

    /** 발급 중(OPEN)인 판매자 쿠폰 */
    static Coupon openSellerCoupon() {
        Coupon coupon = reviewSellerCoupon();
        coupon.markRedisReady(NOW);
        coupon.open(NOW);
        return coupon;
    }
}
