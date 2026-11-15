package com.hapbang.coupon.app.dto;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.coupon.domain.MemberCouponStatus;

// 발급 쿠폰 → 회원 쿠폰 목록 한 건 변환: 쿠폰 종류(판매자·플랫폼)를 쿠폰 정책에서 싣는다
class MemberCouponViewTest {

    private static MemberCoupon issued(long couponId) {
        MemberCoupon memberCoupon = MemberCoupon.issue(MEMBER_ID, couponId, "req-1", NOW, NOW.plusSeconds(3_600), NOW);
        ReflectionTestUtils.setField(memberCoupon, "id", 31L);
        return memberCoupon;
    }

    @Test
    void 판매자_쿠폰과_플랫폼_쿠폰의_종류를_싣는다() {
        MemberCouponView seller = MemberCouponView.of(issued(5L), sellerCoupon());
        MemberCouponView platform = MemberCouponView.of(issued(6L), platformCoupon());

        assertThat(seller.couponType()).isEqualTo(CouponType.SELLER);
        assertThat(platform.couponType()).isEqualTo(CouponType.PLATFORM);
        assertThat(seller.memberCouponId()).isEqualTo(31L);
        assertThat(seller.couponId()).isEqualTo(5L);
        assertThat(seller.couponName()).isEqualTo(COUPON_NAME);
        assertThat(seller.status()).isEqualTo(MemberCouponStatus.AVAILABLE);
        assertThat(seller.expiresAt()).isEqualTo(NOW.plusSeconds(3_600));
    }
}
