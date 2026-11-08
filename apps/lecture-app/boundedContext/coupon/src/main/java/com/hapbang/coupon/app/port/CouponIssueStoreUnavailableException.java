package com.hapbang.coupon.app.port;

/** 발급 저장소(Redis)에 닿지 못했거나 결과를 확인하지 못했다. 성공으로 간주하지 않는다 */
public class CouponIssueStoreUnavailableException extends RuntimeException {

    public CouponIssueStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
