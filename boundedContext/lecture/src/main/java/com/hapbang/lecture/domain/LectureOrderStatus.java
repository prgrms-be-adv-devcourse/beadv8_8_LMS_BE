package com.hapbang.lecture.domain;

/**
 * 주문 상태. 주문은 HP 보류가 성공한 경우에만 생기므로 결제 대기·실패 상태가 없다.
 * 모든 항목이 구매 취소·반환되면 {@code CANCELED}가 된다.
 */
public enum LectureOrderStatus {
    PAID,
    CANCELED
}
