package com.hapbang.coupon.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.CouponDltLogView;
import com.hapbang.coupon.domain.CouponDltKind;
import com.hapbang.coupon.domain.CouponDltStatus;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 DLT 조회·재처리·영구 거절 진입점. Kafka·Redis 호출이 섞이므로 메서드 전체에 트랜잭션을 걸지 않는다(UseCase 안에서 짧게 나눈다).
 * 재처리·영구 거절은 기록을 남긴 소비자({@link CouponDltKind})로 갈린다: 발급 요청은 원 토픽 재발행·Redis 보상, 주문 결제·취소 완료는
 * 저장한 원문으로 소비 UseCase 재실행·기록만 거절한다.
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class CouponDltAdminFacade {

    private final CouponDltQuery couponDltQuery;
    private final CouponDltReplayUseCase couponDltReplayUseCase;
    private final CouponDltRejectUseCase couponDltRejectUseCase;
    private final CouponOrderDltReplayUseCase couponOrderDltReplayUseCase;
    private final CouponOrderDltRejectUseCase couponOrderDltRejectUseCase;

    public Page<CouponDltLogView> list(CouponDltStatus status, Pageable pageable) {
        return couponDltQuery.list(status, pageable);
    }

    public CouponDltLogView replay(long adminId, long dltId) {
        return switch (couponDltQuery.kindOf(dltId)) {
            case ISSUE -> couponDltReplayUseCase.replay(adminId, dltId);
            case ORDER -> couponOrderDltReplayUseCase.replay(adminId, dltId);
        };
    }

    public CouponDltLogView reject(long adminId, long dltId, String reason) {
        return switch (couponDltQuery.kindOf(dltId)) {
            case ISSUE -> couponDltRejectUseCase.reject(adminId, dltId, reason);
            case ORDER -> couponOrderDltRejectUseCase.reject(adminId, dltId, reason);
        };
    }
}
