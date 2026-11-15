package com.hapbang.coupon.domain.usage;

import com.hapbang.coupon.domain.CouponDomainError;
import com.hapbang.coupon.domain.CouponDomainException;

import lombok.Getter;

/**
 * 쿠폰 사용 규칙(적용 대상·사용 가능·중첩) 위반. 어느 발급 쿠폰 때문에 거절했는지 함께 담는다.
 * 쿠폰 global의 핸들러가 이 ID를 응답의 {@code memberCouponId} 속성으로 싣는다.
 */
@Getter
public class CouponUsageViolationException extends CouponDomainException {

    private final long memberCouponId;

    public CouponUsageViolationException(CouponDomainError error, long memberCouponId) {
        super(error);
        this.memberCouponId = memberCouponId;
    }
}
