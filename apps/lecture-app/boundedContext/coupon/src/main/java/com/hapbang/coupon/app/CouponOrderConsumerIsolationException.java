package com.hapbang.coupon.app;

/**
 * 다시 시도해도 결과가 같아 재시도 없이 바로 DLT로 격리할 주문 결제·취소 완료 이벤트(형식 오류, 없는 사용 시도, 회원·주문이 사용 시도와
 * 다름, 같은 eventId의 다른 내용). Kafka 오류 처리기가 재시도하지 않는 예외로 분류한다. 격리된 이벤트는 관리자가 재처리하거나 영구
 * 거절한다.
 */
public class CouponOrderConsumerIsolationException extends RuntimeException {

    public CouponOrderConsumerIsolationException(String message) {
        super(message);
    }
}
