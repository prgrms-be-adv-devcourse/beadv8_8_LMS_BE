package com.hapbang.shared.appearance.domain;

/**
 * 출연 수익 종류 (#301). 강의의 수익 종류({@code LECTURE_SALE})와 같은 필드({@code earningType})로 나간다.
 * 상수 이름은 Billing과 합의한 응답 문자열이다.
 */
public enum AppearanceEarningType {
    /** 출연료. 출연 확인 때 지급한다 */
    APPEARANCE_FEE,
    /** 호스트 일방 취소 위약금. 일방 취소된 출연은 출연료가 아니라 위약금 한 건으로만 센다 */
    APPEARANCE_PENALTY
}
