package com.hapbang.coupon.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.ReconcileHoldReleaseView;

import lombok.RequiredArgsConstructor;

/** 관리자 발급 대사 진입점. Redis 호출만 하므로 트랜잭션을 걸지 않는다 */
@Service
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class CouponReconciliationAdminFacade {

    private final CouponReconciliationUseCase couponReconciliationUseCase;

    public ReconcileHoldReleaseView releaseReconcileHold(long adminId, long couponId) {
        return couponReconciliationUseCase.releaseReconcileHold(adminId, couponId);
    }
}
