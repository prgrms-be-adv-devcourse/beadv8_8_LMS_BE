package com.hapbang.integration.lecture.billing;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.lecture.out.billing.BillingCallResult;
import com.hapbang.shared.billing.domain.BillingFailureCode;

// billing 업무 예외를 모듈 간 실패 결과로 바꾼다 (D62).
// billing 오류 코드를 default 없이 모두 나열해 billing에 새 코드가 생기면 컴파일 오류로 드러나게 한다.
// 강의 보류·반환·지급에서 나올 수 없는 코드는 업무 실패로 보이지 않게 IllegalStateException(500)으로 끝내고 원래 예외를 cause로 남긴다.
final class BillingFailureMapper {

    private BillingFailureMapper() {
    }

    static <T> BillingCallResult.Failed<T> toFailed(BillingException exception) {
        BillingErrorCode code = (BillingErrorCode) exception.getErrorCode();
        return new BillingCallResult.Failed<>(toFailureCode(code, exception), exception.getMessage());
    }

    private static BillingFailureCode toFailureCode(BillingErrorCode code, BillingException exception) {
        return switch (code) {
            case WALLET_NOT_FOUND -> BillingFailureCode.WALLET_NOT_FOUND;
            case INSUFFICIENT_BALANCE -> BillingFailureCode.INSUFFICIENT_BALANCE;
            case BALANCE_OVERFLOW -> BillingFailureCode.BALANCE_OVERFLOW;
            case INVALID_POINT_AMOUNT -> BillingFailureCode.INVALID_POINT_AMOUNT;
            case IDEMPOTENCY_KEY_CONFLICT -> BillingFailureCode.IDEMPOTENCY_KEY_CONFLICT;
            case HOLD_NOT_FOUND -> BillingFailureCode.HOLD_NOT_FOUND;
            case HOLD_OWNER_MISMATCH -> BillingFailureCode.HOLD_OWNER_MISMATCH;
            case HOLD_ALREADY_CLOSED -> BillingFailureCode.HOLD_ALREADY_CLOSED;
            case HOLD_TYPE_NOT_SUPPORTED -> BillingFailureCode.HOLD_TYPE_NOT_SUPPORTED;
            case HOLD_CHARGE_NOT_FOUND -> BillingFailureCode.HOLD_CHARGE_NOT_FOUND;
            case HOLD_STATE_INCONSISTENT -> BillingFailureCode.HOLD_STATE_INCONSISTENT;
            case CHARGE_PRINCIPAL_MISMATCH -> BillingFailureCode.CHARGE_PRINCIPAL_MISMATCH;
            case RELEASE_AMOUNT_EXCEEDS_HELD -> BillingFailureCode.RELEASE_AMOUNT_EXCEEDS_HELD;
            case PAYOUT_AMOUNT_EXCEEDS_HELD -> BillingFailureCode.PAYOUT_AMOUNT_EXCEEDS_HELD;
            case PAYOUT_TO_PAYER -> BillingFailureCode.PAYOUT_TO_PAYER;
            case PAYOUT_SOURCE_MISMATCH -> BillingFailureCode.PAYOUT_SOURCE_MISMATCH;
            case PAYOUT_ALREADY_SETTLED -> BillingFailureCode.PAYOUT_ALREADY_SETTLED;
            case PENALTY_BASE_EXCEEDS_HELD -> BillingFailureCode.PENALTY_BASE_EXCEEDS_HELD;
            case PENALTY_AMOUNT_ZERO -> BillingFailureCode.PENALTY_AMOUNT_ZERO;
            // 역분개·확정 차감·HP 충전 전용 코드. 강의의 보류·반환·지급에서는 나올 수 없다
            case ORIGINAL_LEDGER_NOT_FOUND,
                 INVALID_REVERSAL,
                 CAPTURE_AMOUNT_EXCEEDS_HELD,
                 CHARGE_UNAVAILABLE,
                 CHARGE_AMOUNT_INVALID,
                 CHARGE_NOT_FOUND,
                 CHARGE_AMOUNT_MISMATCH,
                 CHARGE_PAYMENT_KEY_CONFLICT,
                 CHARGE_ALREADY_FAILED,
                 CHARGE_PAYMENT_REJECTED,
                 CHARGE_STATE_INCONSISTENT ->
                    throw new IllegalStateException("강의 billing 연동에서 발생할 수 없는 오류입니다: " + code, exception);
        };
    }
}
