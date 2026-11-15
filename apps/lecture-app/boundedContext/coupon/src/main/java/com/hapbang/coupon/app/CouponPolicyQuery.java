package com.hapbang.coupon.app;

import java.util.List;

import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.CouponView;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.out.CouponRepository;

import lombok.RequiredArgsConstructor;

/** 판매자·관리자의 쿠폰 정책 조회. 목록은 최신순이고 상한 {@link CouponSupport#MAX_LIST_SIZE}건이다. 역할 검사는 Facade가 한다 */
@Service
@RequiredArgsConstructor
public class CouponPolicyQuery {

    private final CouponRepository couponRepository;
    private final CouponSupport couponSupport;

    /** 판매자가 신청한 판매자 쿠폰. @param status 없으면 전체 */
    public List<CouponView> sellerCoupons(long sellerId, CouponStatus status) {
        return toViews(couponRepository.findSellerCoupons(sellerId, status, CouponSupport.LIST_LIMIT));
    }

    /** 판매자 쿠폰 상세. 신청한 판매자 본인이 아니면 403 */
    public CouponView sellerCoupon(long sellerId, long couponId) {
        Coupon coupon = couponSupport.findCoupon(couponId);
        couponSupport.requireOwner(coupon, sellerId);
        return CouponView.from(coupon);
    }

    /** 모든 쿠폰(판매자·플랫폼). @param status 없으면 전체 */
    public List<CouponView> allCoupons(CouponStatus status) {
        return toViews(couponRepository.findAllCoupons(status, CouponSupport.LIST_LIMIT));
    }

    public CouponView coupon(long couponId) {
        return CouponView.from(couponSupport.findCoupon(couponId));
    }

    private static List<CouponView> toViews(List<Coupon> coupons) {
        return coupons.stream().map(CouponView::from).toList();
    }
}
