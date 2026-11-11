package com.hapbang.coupon.domain.usage;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.DiscountType;
import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.coupon.domain.MemberCouponStatus;

// 발급 쿠폰과 쿠폰 정책으로 계산용 값을 만든다
class UsableCouponTest {

    private static final Instant EXPIRES_AT = NOW.plusSeconds(3_600);

    private static Coupon platformCouponWithId(long id) {
        Coupon coupon = Coupon.createPlatform(ADMIN_ID, anyLectureRate().stackable(true).build(), NOW);
        ReflectionTestUtils.setField(coupon, "id", id);
        return coupon;
    }

    private static MemberCoupon memberCouponWithId(long id, long couponId) {
        MemberCoupon memberCoupon = MemberCoupon.issue(MEMBER_ID, couponId, "req-1", NOW, EXPIRES_AT, NOW);
        ReflectionTestUtils.setField(memberCoupon, "id", id);
        return memberCoupon;
    }

    @Test
    void 발급_쿠폰의_상태와_세대에_쿠폰_정책의_할인_조건을_합친다() {
        UsableCoupon usable = UsableCoupon.of(memberCouponWithId(30L, 5L), platformCouponWithId(5L));

        assertThat(usable.memberCouponId()).isEqualTo(30L);
        assertThat(usable.memberId()).isEqualTo(MEMBER_ID);
        assertThat(usable.couponId()).isEqualTo(5L);
        assertThat(usable.type()).isEqualTo(CouponType.PLATFORM);
        assertThat(usable.targetType()).isEqualTo(CouponTargetType.ANY_LECTURE);
        assertThat(usable.discountType()).isEqualTo(DiscountType.RATE);
        assertThat(usable.discountValue()).isEqualTo(10);
        assertThat(usable.maxDiscountAmount()).isEqualTo(3_000L);
        assertThat(usable.stackable()).isTrue();
        assertThat(usable.policyVersion()).isEqualTo(Coupon.INITIAL_POLICY_VERSION);
        assertThat(usable.generation()).isZero();
        assertThat(usable.status()).isEqualTo(MemberCouponStatus.AVAILABLE);
        assertThat(usable.expiresAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    void 발급_쿠폰의_쿠폰_정책이_아니면_만들지_않는다() {
        assertThatThrownBy(() -> UsableCoupon.of(memberCouponWithId(30L, 6L), platformCouponWithId(5L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
