package com.hapbang.coupon.out;

import java.time.Instant;

import com.hapbang.coupon.domain.MemberCouponStatus;

/** 예산 해제 CAS 입력. 엔티티 캐시 대신 현재 DB 값을 읽는다. */
public record MemberCouponBudgetState(long id, long couponId, MemberCouponStatus status, long generation,
        Instant expiresAt, long plannedHp, MemberCouponStatus checkedStatus) {

    public MemberCouponBudgetState(long id, long couponId, MemberCouponStatus status, long generation,
                                   Instant expiresAt, long plannedHp) {
        this(id, couponId, status, generation, expiresAt, plannedHp, null);
    }
}
