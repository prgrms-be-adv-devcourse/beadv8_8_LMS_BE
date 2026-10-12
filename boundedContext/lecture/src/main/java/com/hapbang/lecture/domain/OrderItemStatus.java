package com.hapbang.lecture.domain;

/**
 * 주문 항목 상태. 구매 확정·구매 취소·지급·이의 제기는 항목 단위로 일어난다.
 * <ul>
 *     <li>{@code PURCHASED}: 구매(HP 보류)</li>
 *     <li>{@code CONFIRMED}: 구매 확정</li>
 *     <li>{@code CANCELED}: 구매 취소(HP 반환)</li>
 *     <li>{@code RETURNED}: 이의 제기 인정(HP 반환)</li>
 *     <li>{@code PAID_OUT}: 판매자에게 지급</li>
 * </ul>
 */
public enum OrderItemStatus {
    PURCHASED,
    CONFIRMED,
    CANCELED,
    RETURNED,
    PAID_OUT
}
