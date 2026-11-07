package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponCreateTest.assertError;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CouponBudgetTest {

    @Test
    void 특정_강의_정률은_가격_곱하기_할인율_곱하기_수량이다() {
        // 10,000 HP × 10% × 100장
        assertThat(platform(lectureRate().build()).budgetAmount()).isEqualTo(100_000L);
    }

    @ParameterizedTest(name = "가격 {0} × {1}% → 장당 {2}")
    @CsvSource({
            "999, 10, 99",   // 99.9 → 99
            "1000, 10, 100", // 나누어떨어짐
            "1, 99, 0",      // 0.99 → 0
            "10001, 100, 10001"
    })
    void 특정_강의_정률의_장당_예산은_HP_미만을_버린다(long price, long rate, long perCoupon) {
        CouponTerms terms = lectureRate().basePrice(price).discountValue(rate).quantity(3).build();

        assertThat(platform(terms).budgetAmount()).isEqualTo(perCoupon * 3);
    }

    @ParameterizedTest(name = "정액 {0}, 가격 {1} → 장당 {2}")
    @CsvSource({
            "2000, 10000, 2000",   // 정액 < 가격
            "10000, 10000, 10000", // 정액 = 가격
            "15000, 10000, 10000"  // 정액 > 가격: 할인은 가격까지
    })
    void 특정_강의_정액은_정액과_가격_중_작은_값_곱하기_수량이다(long amount, long price, long perCoupon) {
        CouponTerms terms = lectureAmount().discountValue(amount).basePrice(price).quantity(100).build();

        assertThat(platform(terms).budgetAmount()).isEqualTo(perCoupon * 100);
    }

    @Test
    void 모든_강의_정액은_정액_곱하기_수량이다() {
        // 1,500 HP × 100장
        assertThat(platform(anyLectureAmount().build()).budgetAmount()).isEqualTo(150_000L);
    }

    @Test
    void 모든_강의_정률은_최대_할인액_곱하기_수량이다() {
        // 3,000 HP × 100장 (할인율과 무관)
        assertThat(platform(anyLectureRate().discountValue(50).build()).budgetAmount()).isEqualTo(300_000L);
    }

    @Test
    void 판매자_쿠폰에는_예산이_없다() {
        assertError(() -> sellerCoupon().budgetAmount(), CouponDomainError.BUDGET_NOT_APPLICABLE);
        assertError(() -> sellerCoupon().budgetReserveWorkId(), CouponDomainError.BUDGET_NOT_APPLICABLE);
    }

    @Test
    void 예산액이_long을_넘으면_플랫폼_쿠폰_발행을_거절한다() {
        CouponTerms perCouponOverflow = anyLectureRate().maxDiscountAmount(Long.MAX_VALUE).quantity(2).build();
        CouponTerms priceTimesRateOverflow = lectureRate().basePrice(Long.MAX_VALUE / 50).discountValue(100).build();

        assertError(() -> Coupon.createPlatform(ADMIN_ID, perCouponOverflow, NOW), CouponDomainError.BUDGET_OVERFLOW);
        assertError(() -> Coupon.createPlatform(ADMIN_ID, priceTimesRateOverflow, NOW),
                CouponDomainError.BUDGET_OVERFLOW);
    }

    @Test
    void 예산액이_long_최댓값이면_발행한다() {
        CouponTerms terms = anyLectureRate().maxDiscountAmount(Long.MAX_VALUE).quantity(1).build();

        assertThat(Coupon.createPlatform(ADMIN_ID, terms, NOW).budgetAmount()).isEqualTo(Long.MAX_VALUE);
    }

    private static Coupon platform(CouponTerms terms) {
        return Coupon.createPlatform(ADMIN_ID, terms, NOW);
    }
}
