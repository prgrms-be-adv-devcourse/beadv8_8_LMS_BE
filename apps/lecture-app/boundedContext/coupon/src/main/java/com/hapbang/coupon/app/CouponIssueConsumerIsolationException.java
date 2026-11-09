package com.hapbang.coupon.app;

/**
 * 다시 시도해도 결과가 같아 재시도 없이 바로 DLT로 격리할 발급 요청(형식 오류, 없는 쿠폰, epoch 불일치, 같은 요청 ID의 다른 내용).
 * Kafka 오류 처리기가 재시도하지 않는 예외로 분류한다. 격리된 요청은 관리자가 재발행하거나 영구 거절한다.
 */
public class CouponIssueConsumerIsolationException extends RuntimeException {

    public CouponIssueConsumerIsolationException(String message) {
        super(message);
    }

    public CouponIssueConsumerIsolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
