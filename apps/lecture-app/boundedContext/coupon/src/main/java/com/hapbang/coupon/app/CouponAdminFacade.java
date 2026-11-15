package com.hapbang.coupon.app;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.CouponCreateCommand;
import com.hapbang.coupon.app.dto.CouponView;
import com.hapbang.coupon.domain.CouponStatus;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 쿠폰 심사(승인·거절)와 플랫폼 쿠폰 발행·운영(열기·중단·재개·종료), 쿠폰 조회 진입점.
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
    private final CouponPolicyQuery couponPolicyQuery;

    /** 모든 쿠폰(최신순). @param status 없으면 전체 */
    public List<CouponView> coupons(CouponStatus status) {
        return couponPolicyQuery.allCoupons(status);
    }

    public CouponView coupon(long couponId) {
        return couponPolicyQuery.coupon(couponId);
    }

    public CouponView createPlatform(long adminId, CouponCreateCommand command) {
        return couponCreateUseCase.createPlatform(adminId, command);
    }

    public CouponView open(long adminId, long couponId) {
        return couponOpenUseCase.open(adminId, couponId);
    }

    public CouponView approve(long adminId, long couponId) {
        return couponOpenUseCase.approve(adminId, couponId);
    }

    public CouponView reject(long adminId, long couponId, String reason) {
        return couponReviewUseCase.reject(adminId, couponId, reason);
    }

    public CouponView suspend(long adminId, long couponId) {
        return couponOperateUseCase.suspend(adminId, couponId);
    }

    public CouponView resume(long adminId, long couponId) {
        return couponOperateUseCase.resume(adminId, couponId);
    }

    public CouponView close(long adminId, long couponId) {
        return couponOperateUseCase.close(adminId, couponId);
    }
}
