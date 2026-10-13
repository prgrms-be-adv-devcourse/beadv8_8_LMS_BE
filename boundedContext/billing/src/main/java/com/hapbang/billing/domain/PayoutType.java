package com.hapbang.billing.domain;

// 보류 HP의 지급 목적을 정상 지급과 호스트 출연 취소 위약금 지급으로 구분한다.
public enum PayoutType {
    /** 정상 지급. 출연 확인·강의 판매 등 거래가 끝나 지급한다 */
    NORMAL,
    /** 위약금 지급. 출연 성립 후 호스트가 일방 취소할 때 게스트에게 지급한다 */
    PENALTY
}
