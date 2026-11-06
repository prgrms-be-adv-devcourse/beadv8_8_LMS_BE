package com.hapbang.coupon.domain;

import lombok.Getter;

/** 쿠폰 도메인 규칙 위반. 어떤 규칙인지는 {@link #getError()}로 구분한다 */
@Getter
public class CouponDomainException extends RuntimeException {

    private final CouponDomainError error;

    public CouponDomainException(CouponDomainError error) {
        super(error.getMessage());
        this.error = error;
    }

    /** 메시지만 상황에 맞게 바꾼다. 구분 값({@link #getError()})은 같다 */
    public CouponDomainException(CouponDomainError error, String detail) {
        super(detail);
        this.error = error;
    }
}
