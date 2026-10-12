package com.hapbang.billing.domain;

// 보류 HP의 지급 목적을 정상 지급과 호스트 출연 취소 위약금 지급으로 구분한다.
public enum PayoutType {
    NORMAL,
    PENALTY
}
