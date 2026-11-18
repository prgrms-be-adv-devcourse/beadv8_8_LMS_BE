package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;

// 사용 상태 전이(예약·해제·사용 완료·복원·만료)는 저장소의 조건부 갱신이 맡으므로 MemberCouponRepositoryTest가 실제 DB로 본다
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
    void 기한이_지났다는_판정은_만료_시각과_같은_때부터다() {
        assertThat(MemberCoupon.isExpiredAt(EXPIRES_AT, EXPIRES_AT.minusNanos(1))).isFalse();
        assertThat(MemberCoupon.isExpiredAt(EXPIRES_AT, EXPIRES_AT)).isTrue();
        assertThat(MemberCoupon.isExpiredAt(EXPIRES_AT, EXPIRES_AT.plusSeconds(1))).isTrue();
    }

    @Test
    void 해제_복원_때_돌아갈_상태는_만료_시각_전이면_AVAILABLE_그때부터_EXPIRED다() {
        assertThat(MemberCoupon.returnStatusOf(EXPIRES_AT, EXPIRES_AT.minusNanos(1)))
                .isEqualTo(MemberCouponStatus.AVAILABLE);
        assertThat(MemberCoupon.returnStatusOf(EXPIRES_AT, EXPIRES_AT)).isEqualTo(MemberCouponStatus.EXPIRED);
    }
}
