package com.hapbang.shared.billing.domain;

/**
 * 지갑 내부 API가 보류·반환·지급·위약금 정산·보류 무효화에서 응답할 수 있는 실패 코드 (D62, D64, #479).
 * 지갑 {@code BillingErrorCode} 전체가 아니라 이 네 호출에서 나올 수 있는 코드만 같은 이름으로 둔다.
 * 지갑 코드와의 대조는 지갑 쪽(#273)이 {@code default} 없는 {@code switch}와 테스트로 고정한다.
 * 실패를 재시도할지·거절로 볼지는 호출한 쪽이 작업 유형별로 정한다(강의 D57).
 */
public enum BillingFailureCode {

    // 지갑·잔액
    WALLET_NOT_FOUND,
    INSUFFICIENT_BALANCE,
    BALANCE_OVERFLOW,
    INVALID_POINT_AMOUNT,

    // 멱등: 같은 키로 다른 내용을 요청했다
    IDEMPOTENCY_KEY_CONFLICT,

    // 보류
    HOLD_NOT_FOUND,
    HOLD_OWNER_MISMATCH,
    HOLD_ALREADY_CLOSED,
    HOLD_TYPE_NOT_SUPPORTED,
    HOLD_CHARGE_NOT_FOUND,
    HOLD_STATE_INCONSISTENT,
    CHARGE_PRINCIPAL_MISMATCH,
    // 보류 무효화된 참조 대상에 보류를 요청했다(409, 보류를 만들지 않음, #479)
    HOLD_VOIDED,

    // 반환·지급
    RELEASE_AMOUNT_EXCEEDS_HELD,
    PAYOUT_AMOUNT_EXCEEDS_HELD,
    PAYOUT_TO_PAYER,
    PAYOUT_SOURCE_MISMATCH,
    PAYOUT_ALREADY_SETTLED,

    // 위약금 정산 (출연)
    PENALTY_BASE_EXCEEDS_HELD,
    PENALTY_AMOUNT_ZERO,

    // 락 대기 초과(503). 지갑이 처리하지 않고 롤백했으므로 같은 요청을 다시 보내면 된다 (#273)
    HP_LOCK_TIMEOUT,

    /** 위 목록에 없는 코드이거나 오류 본문에 코드가 없다(내부 API 키 오류 등). 원래 문자열은 {@code Failed.rawCode()}에 있다 */
    UNMAPPED;

    /** 오류 응답의 {@code code} 문자열을 바꾼다. 목록에 없거나 {@code null}이면 {@link #UNMAPPED}다 */
    public static BillingFailureCode from(String code) {
        if (code == null) {
            return UNMAPPED;
        }
        for (BillingFailureCode value : values()) {
            if (value != UNMAPPED && value.name().equals(code)) {
                return value;
            }
        }
        return UNMAPPED;
    }
}
