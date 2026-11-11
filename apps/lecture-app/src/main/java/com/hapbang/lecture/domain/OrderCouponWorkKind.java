package com.hapbang.lecture.domain;

import java.util.Locale;

/** 주문의 쿠폰 작업 종류 */
public enum OrderCouponWorkKind {
    /** 주문한 쿠폰을 예약한다. 예약 결과가 저장된 뒤에만 HP 보류를 등록한다 */
    RESERVE,
    /** 결제 실패가 확정된 주문에서 예약한 쿠폰을 돌려준다 */
    RELEASE;

    /** 주문의 이 종류 작업 ID(쿠폰 모듈 멱등 키): {@code lecture-order:<주문 ID>:reserve} 또는 {@code :release} */
    String workIdOf(Long orderId) {
        return "lecture-order:" + orderId + ":" + name().toLowerCase(Locale.ROOT);
    }
}
