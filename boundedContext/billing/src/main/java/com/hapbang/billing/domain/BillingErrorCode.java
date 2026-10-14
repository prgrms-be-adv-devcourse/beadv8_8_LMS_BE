package com.hapbang.billing.domain;

import org.springframework.http.HttpStatus;

import com.hapbang.global.exception.BaseErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// Billing API 오류 코드와 HTTP 상태·메시지를 정의한다.
@Getter
@RequiredArgsConstructor
public enum BillingErrorCode implements BaseErrorCode {
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "지갑을 찾을 수 없습니다."),
    ORIGINAL_LEDGER_NOT_FOUND(HttpStatus.NOT_FOUND, "역분개할 원장 항목을 찾을 수 없습니다."),
    INVALID_REVERSAL(HttpStatus.BAD_REQUEST, "역분개 요청이 원본 원장 항목과 맞지 않습니다."),
    INVALID_POINT_AMOUNT(HttpStatus.BAD_REQUEST, "HP 금액이 올바르지 않습니다."),
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "HP 잔액이 부족합니다."),
    BALANCE_OVERFLOW(HttpStatus.CONFLICT, "HP 잔액이 허용 범위를 넘습니다."),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "같은 작업 키로 다른 내용의 원장 기록이 요청되었습니다."),
    HOLD_NOT_FOUND(HttpStatus.NOT_FOUND, "HP 보류를 찾을 수 없습니다."),
    HOLD_OWNER_MISMATCH(HttpStatus.CONFLICT, "요청한 회원의 HP 보류가 아닙니다."),
    HOLD_ALREADY_CLOSED(HttpStatus.CONFLICT, "남은 보류 HP가 없어 더 처리할 수 없습니다."),
    RELEASE_AMOUNT_EXCEEDS_HELD(HttpStatus.CONFLICT, "반환 금액이 남은 보류 HP보다 많습니다."),
    PAYOUT_AMOUNT_EXCEEDS_HELD(HttpStatus.CONFLICT, "지급 금액이 남은 보류 HP보다 많습니다."),
    PENALTY_BASE_EXCEEDS_HELD(HttpStatus.CONFLICT, "위약금 정산 기준액이 남은 보류 HP보다 많습니다."),
    PENALTY_AMOUNT_ZERO(HttpStatus.BAD_REQUEST, "위약금이 0 HP가 되는 정산 기준액은 정산할 수 없습니다."),
    PAYOUT_TO_PAYER(HttpStatus.BAD_REQUEST, "보류한 회원 자신에게는 지급할 수 없습니다."),
    PAYOUT_SOURCE_MISMATCH(HttpStatus.BAD_REQUEST, "지급 원천이 보류의 거래 대상과 맞지 않습니다."),
    PAYOUT_ALREADY_SETTLED(HttpStatus.CONFLICT, "같은 출연 계약·주문 항목(또는 같은 출연 요청)에 이미 지급했습니다."),
    SETTLEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "지급 건을 찾을 수 없습니다."),
    SETTLEMENT_AMOUNT_MISMATCH(HttpStatus.CONFLICT, "정산 금액이 주문 항목의 보류 HP와 맞지 않습니다."),
    SETTLEMENT_INVALID_STATE(HttpStatus.CONFLICT, "지급 건의 현재 상태에서는 요청한 처리를 할 수 없습니다."),
    // 아래 500 코드는 불변식이 깨진 상황이라 응답 detail에 내부 상태를 드러내지 않고, 원인은 code와 로그로 구분한다.
    CHARGE_PRINCIPAL_MISMATCH(HttpStatus.INTERNAL_SERVER_ERROR, "HP 처리 중 오류가 발생했습니다."),
    HOLD_STATE_INCONSISTENT(HttpStatus.INTERNAL_SERVER_ERROR, "HP 처리 중 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;
}
