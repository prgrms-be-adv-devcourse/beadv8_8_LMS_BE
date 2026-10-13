package com.hapbang.billing.domain;

// 보류 상세에 기록되는 후속 처리를 결제자에게 반환하는 동작과 수령자에게 지급하는 동작으로 구분한다.
public enum HoldDetailActionType {
    /** 반환. 보류 HP를 결제한 회원에게 차감했던 HP 종류 그대로 되돌린다 */
    RELEASE,
    /** 지급. 보류 HP를 받는 쪽(판매자·게스트)의 수익 HP로 옮긴다 */
    PAYOUT
}
