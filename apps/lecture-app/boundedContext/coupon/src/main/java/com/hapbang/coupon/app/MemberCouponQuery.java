package com.hapbang.coupon.app;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.MemberCouponView;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.config.CouponPlatformFundingProperties;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.coupon.out.OwnedCoupon;

/**
 * 회원의 발급 쿠폰 조회. 쿠폰함(내 쿠폰 목록)은 모든 종류를 보여 준다. 강의에 쓸 수 있는 쿠폰은 플랫폼 재원이 꺼져 있으면
 * ({@link CouponPlatformFundingProperties}) 플랫폼 쿠폰을 빼고 돌려준다(그 쿠폰으로는 주문할 수 없다).
 */
@Service
public class MemberCouponQuery {

    private final MemberCouponRepository memberCouponRepository;
    private final CouponPlatformFundingProperties platformFunding;
    private final Clock clock;

    public MemberCouponQuery(MemberCouponRepository memberCouponRepository,
                             CouponPlatformFundingProperties platformFunding,
                             @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.memberCouponRepository = memberCouponRepository;
        this.platformFunding = platformFunding;
        this.clock = clock;
    }

    /** @param status 없으면 전체 */
    public List<MemberCouponView> myCoupons(long memberId, MemberCouponStatus status) {
        return toViews(memberCouponRepository.findMyCoupons(memberId, status));
    }

    /** 이 강의에 지금 쓸 수 있는 발급 쿠폰(만료 임박순). 플랫폼 재원이 꺼져 있으면 플랫폼 쿠폰은 뺀다 */
    public List<MemberCouponView> usableFor(long memberId, long lectureId) {
        List<OwnedCoupon> usable = memberCouponRepository.findUsableFor(memberId, lectureId, clock.instant());
        if (!platformFunding.platformFundingEnabled()) {
            usable = usable.stream().filter(owned -> owned.coupon().getType() != CouponType.PLATFORM).toList();
        }
        return toViews(usable);
    }

    private static List<MemberCouponView> toViews(List<OwnedCoupon> owned) {
        return owned.stream()
                .map(each -> MemberCouponView.of(each.memberCoupon(), each.coupon()))
                .toList();
    }
}
