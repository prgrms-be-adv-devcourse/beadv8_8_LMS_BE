package com.hapbang.billing.domain;

// 지급 예정 수익의 종류(#295). 강의·출연 내부 수익 API의 earningType 이름과 같다. 상수 이름은 응답 문자열이므로 바꾸지 않는다.
public enum EarningType {
    /** 강의 판매 대금 */
    LECTURE_SALE,
    /** 출연료 */
    APPEARANCE_FEE,
    /** 호스트 일방 취소 위약금 */
    APPEARANCE_PENALTY
}
