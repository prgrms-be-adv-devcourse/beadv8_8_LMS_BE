package com.hapbang.coupon.app;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.CouponCreateCommand;
import com.hapbang.coupon.app.dto.CouponView;
import com.hapbang.coupon.domain.CouponStatus;

import lombok.RequiredArgsConstructor;

/** 판매자 쿠폰 신청·심사 요청·중단·조회 진입점. 역할 검사는 여기서, 본인 쿠폰 여부는 UseCase·Query가 한다 */
@Service
@RequiredArgsConstructor
public class CouponSellerFacade {

    private static final String SELLER = "hasRole('VERIFIED_INFLUENCER')";

    private final CouponCreateUseCase couponCreateUseCase;
    private final CouponReviewUseCase couponReviewUseCase;
    private final CouponOperateUseCase couponOperateUseCase;
    private final CouponPolicyQuery couponPolicyQuery;

    @PreAuthorize(SELLER)
    public CouponView create(long sellerId, CouponCreateCommand command) {
        return couponCreateUseCase.createSeller(sellerId, command);
    }

    @PreAuthorize(SELLER)
    public CouponView submitReview(long sellerId, long couponId) {
        return couponReviewUseCase.submitReview(sellerId, couponId);
    }

    @PreAuthorize(SELLER)
    public CouponView suspend(long sellerId, long couponId) {
        return couponOperateUseCase.suspendBySeller(sellerId, couponId);
    }

    /** 본인이 신청한 쿠폰(최신순). @param status 없으면 전체 */
    @PreAuthorize(SELLER)
    public List<CouponView> myCoupons(long sellerId, CouponStatus status) {
        return couponPolicyQuery.sellerCoupons(sellerId, status);
    }

    /** 본인이 신청한 쿠폰 상세. 본인 것이 아니면 403 */
    @PreAuthorize(SELLER)
    public CouponView myCoupon(long sellerId, long couponId) {
        return couponPolicyQuery.sellerCoupon(sellerId, couponId);
    }
}
