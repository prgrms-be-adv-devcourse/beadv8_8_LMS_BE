package com.hapbang.appearance.domain;

/**
 * 출연 수익 종류 (#301). Billing 공통 계약 {@code EarningType}의 {@code APPEARANCE_FEE}·{@code APPEARANCE_PENALTY}에 대응한다.
 * <ul>
 *     <li>FEE: 출연료 (출연 확인 때 지급)</li>
 *     <li>PENALTY: 호스트 일방 취소 위약금. 일방 취소된 출연은 출연료가 아니라 위약금 한 건으로만 센다</li>
 * </ul>
 */
public enum AppearanceEarningType {
    FEE,
    PENALTY
}
