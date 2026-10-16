package com.hapbang.billing.domain;

// 지급 예정 수익의 출처(#295). 강의·출연 내부 수익 API의 sourceType 이름과 같다. 상수 이름은 응답 문자열이므로 바꾸지 않는다.
public enum EarningSourceType {
    /** 강의 판매. sourceId는 주문 항목 ID */
    LECTURE,
    /** 출연. sourceId는 출연 ID */
    APPEARANCE
}
