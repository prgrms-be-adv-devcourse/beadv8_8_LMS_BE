package com.hapbang.coupon.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.CouponDltLogView;
import com.hapbang.coupon.domain.CouponDltStatus;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 DLT 조회·재발행·영구 거절 진입점. Kafka·Redis 호출이 섞이므로 메서드 전체에 트랜잭션을 걸지 않는다(UseCase 안에서 짧게 나눈다).
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class CouponDltAdminFacade {

    private final CouponDltQuery couponDltQuery;
    private final CouponDltReplayUseCase couponDltReplayUseCase;
    private final CouponDltRejectUseCase couponDltRejectUseCase;

    public Page<CouponDltLogView> list(CouponDltStatus status, Pageable pageable) {
        return couponDltQuery.list(status, pageable);
    }

    public CouponDltLogView replay(long adminId, long dltId) {
        return couponDltReplayUseCase.replay(adminId, dltId);
    }

    public CouponDltLogView reject(long adminId, long dltId, String reason) {
        return couponDltRejectUseCase.reject(adminId, dltId, reason);
    }
}
