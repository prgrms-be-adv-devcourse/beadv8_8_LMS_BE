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
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "같은 작업 키로 다른 내용의 원장 기록이 요청되었습니다.");

    private final HttpStatus status;
    private final String message;
}
