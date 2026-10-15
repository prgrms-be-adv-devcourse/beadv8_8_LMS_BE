package com.hapbang.shared.billing.domain;

// billing이 보류·반환·지급·위약금 요청을 처리하지 못했을 때 돌려주는 모듈 간 실패 식별자다 (강의 설계 D56·D62).
// billing 내부 오류 코드(HTTP 상태·메시지 포함) 전체가 아니라 이 호출들에서 실제로 나올 수 있는 코드만 둔다.
// HTTP 상태는 두지 않는다. 응답을 어떻게 표현할지는 호출한 모듈이 정한다.
// 상수 이름은 billing 오류 코드 이름과 같고, 모듈 사이에서 문자열로 오갈 수 있으므로 바꾸지 않는다.
public enum BillingFailureCode {
    WALLET_NOT_FOUND,
    INSUFFICIENT_BALANCE,
    BALANCE_OVERFLOW,
    INVALID_POINT_AMOUNT,
    IDEMPOTENCY_KEY_CONFLICT,
    HOLD_NOT_FOUND,
    HOLD_OWNER_MISMATCH,
    HOLD_ALREADY_CLOSED,
    HOLD_TYPE_NOT_SUPPORTED,
    HOLD_CHARGE_NOT_FOUND,
    HOLD_STATE_INCONSISTENT,
    CHARGE_PRINCIPAL_MISMATCH,
    RELEASE_AMOUNT_EXCEEDS_HELD,
    PAYOUT_AMOUNT_EXCEEDS_HELD,
    PAYOUT_TO_PAYER,
    PAYOUT_SOURCE_MISMATCH,
    PAYOUT_ALREADY_SETTLED,
    PENALTY_BASE_EXCEEDS_HELD,
    PENALTY_AMOUNT_ZERO,
    /** 원격(HTTP) 호출이 처음 보는 오류 문자열을 받았을 때만 쓴다. 같은 JVM 호출에서는 쓰지 않는다 */
    UNMAPPED
}
