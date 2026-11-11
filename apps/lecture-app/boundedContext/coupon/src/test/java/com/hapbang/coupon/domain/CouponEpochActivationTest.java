package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CouponEpochActivationTest {

    @Test
    void 복구_재개는_다음_epoch만_활성화하고_다시_불러도_그대로다() {
        Coupon coupon = platformCoupon();

        coupon.activateRecoveredEpoch(2L, NOW);
        coupon.activateRecoveredEpoch(2L, NOW);

        assertThat(coupon.getEpoch()).isEqualTo(2L);
    }

    @Test
    void 다음_값이_아닌_epoch는_활성화하지_않는다() {
        Coupon coupon = platformCoupon();

        assertThatThrownBy(() -> coupon.activateRecoveredEpoch(3L, NOW)).isInstanceOf(CouponDomainException.class);
        assertThatThrownBy(() -> coupon.activateRecoveredEpoch(0L, NOW)).isInstanceOf(CouponDomainException.class);
        assertThat(coupon.getEpoch()).isEqualTo(Coupon.INITIAL_EPOCH);
    }
}
