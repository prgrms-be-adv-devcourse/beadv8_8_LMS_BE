package com.hapbang.lecture.domain;

/**
 * {@code PAID}인 주문의 진행 상황. 저장하지 않고 항목 상태에서 파생한다.
 * <ul>
 *     <li>{@code IN_PROGRESS}: 아직 끝나지 않은 항목(구매, 구매 확정)이 있음</li>
 *     <li>{@code COMPLETED}: 모든 항목이 지급·구매 취소·반환 중 하나이고 지급이 1개 이상</li>
 * </ul>
 */
public enum OrderProgress {
    IN_PROGRESS,
    COMPLETED
}
