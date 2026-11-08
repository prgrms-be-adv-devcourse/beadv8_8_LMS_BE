package com.hapbang.coupon.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.hapbang.coupon.domain.Coupon;

class CouponPolicyPropertiesTest {

    @Test
    void 만료_상한은_기본_3650일이고_1일부터_DB_상한까지만_받는다() {
        assertThat(new CouponPolicyProperties(null).maxExpiryDays()).isEqualTo(Coupon.DB_MAX_EXPIRY_DAYS);
        assertThat(new CouponPolicyProperties(Coupon.DB_MAX_EXPIRY_DAYS).maxExpiryDays())
                .isEqualTo(Coupon.DB_MAX_EXPIRY_DAYS);

        // DB CHECK보다 크면 도메인은 받고 저장에서 실패하므로 시작할 때 거절한다
        assertThatThrownBy(() -> new CouponPolicyProperties(Coupon.DB_MAX_EXPIRY_DAYS + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CouponPolicyProperties(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
