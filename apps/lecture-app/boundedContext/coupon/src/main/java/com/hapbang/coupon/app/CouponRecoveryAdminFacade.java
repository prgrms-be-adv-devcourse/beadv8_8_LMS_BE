package com.hapbang.coupon.app;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.RecoveryRunView;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 Redis 유실 복구(차단 → 경계 확정 → 결과 확정 → 재구축 → 재개, 재구축 전 취소) 진입점. Redis·Kafka 호출이 섞이므로 메서드 전체에 트랜잭션을
 * 걸지 않는다(트랜잭션은 UseCase 안에서 짧게 나눈다).
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class CouponRecoveryAdminFacade {

    private final CouponRecoveryUseCase couponRecoveryUseCase;

    public List<RecoveryRunView> list(long couponId) {
        return couponRecoveryUseCase.list(couponId);
    }

    public RecoveryRunView start(long adminId, long couponId) {
        return couponRecoveryUseCase.start(adminId, couponId);
    }

    public RecoveryRunView fixBoundary(long adminId, long couponId, long recoveryId) {
        return couponRecoveryUseCase.fixBoundary(adminId, couponId, recoveryId);
    }

    public RecoveryRunView confirmResults(long adminId, long couponId, long recoveryId) {
        return couponRecoveryUseCase.confirmResults(adminId, couponId, recoveryId);
    }

    public RecoveryRunView rebuild(long adminId, long couponId, long recoveryId) {
        return couponRecoveryUseCase.rebuild(adminId, couponId, recoveryId);
    }

    public RecoveryRunView abort(long adminId, long couponId, long recoveryId) {
        return couponRecoveryUseCase.abort(adminId, couponId, recoveryId);
    }

    public RecoveryRunView resume(long adminId, long couponId, long recoveryId, String resumeBasis) {
        return couponRecoveryUseCase.resume(adminId, couponId, recoveryId, resumeBasis);
    }
}
