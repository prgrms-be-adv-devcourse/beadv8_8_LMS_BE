package com.hapbang.shared.billing.domain;

/**
 * 지갑 내부 API가 보류·반환·지급·위약금 정산·보류 무효화·탈퇴용 지갑 닫기(#623)·쿠폰 예산 예약·해제에서 응답할 수 있는 실패 코드 (D62, D64, #479).
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

    // 회원 탈퇴 (#623). 탈퇴로 닫힌 지갑이라 보류를 만들지 않았다(409)
    WALLET_CLOSED,
    // 다시 열기를 이미 받은 탈퇴 작업 ID로 닫기가 왔다(409). 지갑은 닫지 않았다
    WITHDRAWAL_ALREADY_REOPENED,

    // 쿠폰 예산 예약·해제
    // 프로모션 지갑의 예약되지 않은 가용 HP가 예약 금액보다 적다(409, 아무것도 바꾸지 않음)
    PROMOTION_BALANCE_INSUFFICIENT,
    // 같은 쿠폰에 다른 작업 ID의 예약이 이미 있다(409). 쿠폰당 예약은 하나다
    BUDGET_RESERVATION_EXISTS,
    // 해제할 예산 예약이 없다(404)
    BUDGET_RESERVATION_NOT_FOUND,
    // 해제 금액이 예약의 남은 예산보다 많다(409, 아무것도 바꾸지 않음)
    BUDGET_RELEASE_EXCEEDS_REMAINING,

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
