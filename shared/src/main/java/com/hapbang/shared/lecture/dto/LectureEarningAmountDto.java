package com.hapbang.shared.lecture.dto;

/**
 * 판매 수익 한 상태의 금액 합계와 건수 (#300).
 *
 * @param amountHp 금액 합계 (HP)
 * @param count    건수 (주문 항목 수)
 */
public record LectureEarningAmountDto(long amountHp, long count) {

    public static final LectureEarningAmountDto ZERO = new LectureEarningAmountDto(0, 0);
}
