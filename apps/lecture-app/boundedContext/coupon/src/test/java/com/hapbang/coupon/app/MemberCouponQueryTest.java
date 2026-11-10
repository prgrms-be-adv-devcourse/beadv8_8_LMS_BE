package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.CLOCK;
import static com.hapbang.coupon.app.CouponUsageFixture.*;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.hapbang.coupon.app.dto.MemberCouponView;
import com.hapbang.coupon.config.CouponPlatformFundingProperties;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.out.MemberCouponRepository;

// 회원 쿠폰 조회 서비스: 플랫폼 재원이 꺼져 있으면 강의에 쓸 수 있는 쿠폰에서 플랫폼 쿠폰을 빼고, 쿠폰함은 그대로 보여 준다.
// 쓸 수 있는 조건(상태·만료·대상)은 저장소 테스트가 본다
class MemberCouponQueryTest {

    private final MemberCouponRepository memberCouponRepository = mock();

    private void givenSellerAndPlatform() {
        var owned = List.of(owned(11L, MEMBER_ID, sellerRateCoupon(1L)),
                owned(12L, MEMBER_ID, platformAnyAmountCoupon(2L)));
        when(memberCouponRepository.findUsableFor(MEMBER_ID, LECTURE_ID, NOW)).thenReturn(owned);
        when(memberCouponRepository.findMyCoupons(MEMBER_ID, null)).thenReturn(owned);
    }

    @Test
    void 플랫폼_재원이_꺼져_있으면_쓸_수_있는_쿠폰에서_플랫폼_쿠폰을_빼고_쿠폰함에는_보인다() {
        givenSellerAndPlatform();
        MemberCouponQuery query = new MemberCouponQuery(memberCouponRepository,
                CouponPlatformFundingProperties.defaults(), CLOCK);

        assertThat(query.usableFor(MEMBER_ID, LECTURE_ID)).extracting(MemberCouponView::couponType)
                .containsExactly(CouponType.SELLER);
        assertThat(query.myCoupons(MEMBER_ID, null)).extracting(MemberCouponView::couponType)
                .containsExactly(CouponType.SELLER, CouponType.PLATFORM);
    }

    @Test
    void 플랫폼_재원이_켜져_있으면_쓸_수_있는_쿠폰에_플랫폼_쿠폰도_넣는다() {
        givenSellerAndPlatform();
        MemberCouponQuery query = new MemberCouponQuery(memberCouponRepository,
                new CouponPlatformFundingProperties(true), CLOCK);

        assertThat(query.usableFor(MEMBER_ID, LECTURE_ID)).extracting(MemberCouponView::memberCouponId)
                .containsExactly(11L, 12L);
    }
}
