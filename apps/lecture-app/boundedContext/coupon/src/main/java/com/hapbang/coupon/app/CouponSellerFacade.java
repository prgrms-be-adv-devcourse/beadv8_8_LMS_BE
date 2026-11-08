package com.hapbang.coupon.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.CouponCreateCommand;
import com.hapbang.coupon.app.dto.CouponView;

import lombok.RequiredArgsConstructor;

/** 판매자 쿠폰 신청·심사 요청·중단 진입점. 역할 검사는 여기서, 본인 쿠폰 여부는 UseCase가 한다 */
@Service
@RequiredArgsConstructor
public class CouponSellerFacade {

    private static final String SELLER = "hasRole('VERIFIED_INFLUENCER')";

    private final CouponCreateUseCase couponCreateUseCase;
    private final CouponReviewUseCase couponReviewUseCase;
    private final CouponOperateUseCase couponOperateUseCase;

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
}
