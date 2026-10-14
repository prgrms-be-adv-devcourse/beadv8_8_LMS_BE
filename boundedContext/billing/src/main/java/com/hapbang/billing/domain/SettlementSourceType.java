package com.hapbang.billing.domain;

// 지급의 근거가 되는 강의 주문 항목·출연 요청·모집 참가자의 종류를 구분한다.
public enum SettlementSourceType {
    /** 강의 주문 항목. 강의 판매 수익을 판매자에게 지급한다(구매일 D+30) */
    LECTURE_ORDER_ITEM,
    /** 출연 요청(1:1)에서 성립한 출연 계약(원천 ID = 출연 계약 ID, 요청서 ID 아님). 출연료 또는 호스트 일방 취소 위약금을 게스트에게 지급한다 */
    APPEARANCE_REQUEST,
    /** 출연 모집(1:N)에서 성립한 게스트별 출연 계약(원천 ID = 출연 ID). 그 게스트에게 지급한다 */
    RECRUIT_APPEARANCE
}
