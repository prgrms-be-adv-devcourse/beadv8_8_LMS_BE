package com.hapbang.appearance.out.billing;

import org.springframework.http.HttpStatus;

import com.hapbang.global.exception.BaseErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * {@link FakeBillingClient}가 내는 오류 코드. Billing 오류 코드({@code BillingErrorCode})의 이름·HTTP 상태·메시지를 그대로 옮겼다.
 * <p>
 * 출연은 Billing 모듈 코드를 참조하지 않으므로 같은 값을 여기에 둔다. Billing 오류 코드가 바뀌면 함께 맞춘다.
 * 출연 연동과 관계없는 코드(충전·환전·역분개·내부 불일치)는 옮기지 않았다.
 */
@Getter
@RequiredArgsConstructor
public enum FakeBillingErrorCode implements BaseErrorCode {
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "지갑을 찾을 수 없습니다."),
    INVALID_POINT_AMOUNT(HttpStatus.BAD_REQUEST, "HP 금액이 올바르지 않습니다."),
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "HP 잔액이 부족합니다."),
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
    PAYOUT_ALREADY_SETTLED(HttpStatus.CONFLICT, "같은 출연 계약·주문 항목(또는 같은 출연 요청)에 이미 지급했습니다.");

    private final HttpStatus status;
    private final String message;
}
