package com.hapbang.coupon.app.port;

/** 복구 경계에 필요한 Kafka 상태를 읽지 못했다. 경계를 모르는 채로 복구를 진행하지 않는다 */
public class CouponIssueBoundaryUnavailableException extends RuntimeException {

    public CouponIssueBoundaryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
