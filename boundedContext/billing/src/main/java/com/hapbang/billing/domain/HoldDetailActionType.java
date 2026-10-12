package com.hapbang.billing.domain;

// 보류 상세에 기록되는 후속 처리를 결제자에게 반환하는 동작과 수령자에게 지급하는 동작으로 구분한다.
public enum HoldDetailActionType {
    RELEASE,
    PAYOUT
}
