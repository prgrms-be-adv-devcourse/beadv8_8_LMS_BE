package com.hapbang.coupon.app;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.MemberCouponView;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.coupon.out.OwnedCoupon;

/** 회원의 발급 쿠폰 조회 */
@Service
public class MemberCouponQuery {

    private final MemberCouponRepository memberCouponRepository;
    private final Clock clock;

    public MemberCouponQuery(MemberCouponRepository memberCouponRepository,
                             @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.memberCouponRepository = memberCouponRepository;
        this.clock = clock;
    }

    /** @param status 없으면 전체 */
    public List<MemberCouponView> myCoupons(long memberId, MemberCouponStatus status) {
        return toViews(memberCouponRepository.findMyCoupons(memberId, status));
    }

    /** 이 강의에 지금 쓸 수 있는 발급 쿠폰(만료 임박순) */
    public List<MemberCouponView> usableFor(long memberId, long lectureId) {
        return toViews(memberCouponRepository.findUsableFor(memberId, lectureId, clock.instant()));
    }

    private static List<MemberCouponView> toViews(List<OwnedCoupon> owned) {
        return owned.stream()
                .map(each -> MemberCouponView.of(each.memberCoupon(), each.coupon()))
                .toList();
    }
}
