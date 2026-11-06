package com.hapbang.coupon.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.CouponCreateCommand;
import com.hapbang.coupon.app.dto.CouponView;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 쿠폰 심사(승인·거절)와 플랫폼 쿠폰 발행·운영(열기·중단·재개·종료) 진입점.
 * HTTP·Redis·예산 호출이 섞이므로 메서드 전체에 트랜잭션을 걸지 않는다(트랜잭션은 UseCase 안에서 짧게 나눈다).
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class CouponAdminFacade {

    private final CouponCreateUseCase couponCreateUseCase;
    private final CouponReviewUseCase couponReviewUseCase;
    private final CouponOpenUseCase couponOpenUseCase;
    private final CouponOperateUseCase couponOperateUseCase;

    public CouponView createPlatform(long adminId, CouponCreateCommand command) {
        return couponCreateUseCase.createPlatform(adminId, command);
    }

    public CouponView open(long couponId) {
        return couponOpenUseCase.open(couponId);
    }

    public CouponView approve(long couponId) {
        return couponOpenUseCase.approve(couponId);
    }

    public CouponView reject(long couponId, String reason) {
        return couponReviewUseCase.reject(couponId, reason);
    }

    public CouponView suspend(long couponId) {
        return couponOperateUseCase.suspend(couponId);
    }

    public CouponView resume(long couponId) {
        return couponOperateUseCase.resume(couponId);
    }

    public CouponView close(long couponId) {
        return couponOperateUseCase.close(couponId);
    }
}
