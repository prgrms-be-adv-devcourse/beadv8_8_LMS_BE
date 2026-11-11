package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class MemberCouponTest {

    private static final Instant EXPIRES_AT = NOW.plusSeconds(3_600);

    @Test
    void 발급_쿠폰은_AVAILABLE_세대_0으로_시작한다() {
        MemberCoupon coupon = MemberCoupon.issue(MEMBER_ID, 5L, "req-1", NOW, EXPIRES_AT, NOW);

        assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.AVAILABLE);
        assertThat(coupon.getGeneration()).isZero();
        assertThat(coupon.getIssuedAt()).isEqualTo(NOW);
        assertThat(coupon.getExpiresAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    void 만료_시각_전까지만_사용할_수_있다() {
        MemberCoupon coupon = MemberCoupon.issue(MEMBER_ID, 5L, "req-1", NOW, EXPIRES_AT, NOW);

        assertThat(coupon.isUsableAt(EXPIRES_AT.minusNanos(1))).isTrue();
        assertThat(coupon.isUsableAt(EXPIRES_AT)).isFalse();
    }
}
