package com.hapbang.lecture.app;

import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.out.billing.BillingCallResult;
import com.hapbang.shared.billing.domain.BillingFailureCode;

/**
 * billing 결과를 강의의 HTTP 표현으로 바꾼다 (D62). UseCase는 billing 결과를 모두 이곳을 거쳐 받는다.
 * <p>
 * 지금은 billing이 강의 트랜잭션에 참여하므로, billing이 실패하면 그 트랜잭션은 이미 rollback-only다.
 * {@link BillingCallResult.Failed}를 받으면 처리를 이어 가지 않고 바로 {@link LectureException}으로 끝내
 * billing 예외를 그대로 전파하던 때와 같은 HTTP 상태·{@code code}·{@code detail}로 응답한다.
 */
final class LectureBillingFailureMapper {

    private LectureBillingFailureMapper() {
    }

    /** 성공이면 값을 돌려주고, 실패면 같은 코드의 강의 예외를 던진다 */
    static <T> T valueOrThrow(BillingCallResult<T> result) {
        return switch (result) {
            case BillingCallResult.Succeeded<T>(T value) -> value;
            case BillingCallResult.Failed<T>(BillingFailureCode code, String detail) ->
                    throw new LectureException(toLectureErrorCode(code), detail);
        };
    }

    static LectureErrorCode toLectureErrorCode(BillingFailureCode code) {
        return switch (code) {
            case WALLET_NOT_FOUND -> LectureErrorCode.WALLET_NOT_FOUND;
            case INSUFFICIENT_BALANCE -> LectureErrorCode.INSUFFICIENT_BALANCE;
            case BALANCE_OVERFLOW -> LectureErrorCode.BALANCE_OVERFLOW;
            case INVALID_POINT_AMOUNT -> LectureErrorCode.INVALID_POINT_AMOUNT;
            case IDEMPOTENCY_KEY_CONFLICT -> LectureErrorCode.IDEMPOTENCY_KEY_CONFLICT;
            case HOLD_NOT_FOUND -> LectureErrorCode.HOLD_NOT_FOUND;
            case HOLD_OWNER_MISMATCH -> LectureErrorCode.HOLD_OWNER_MISMATCH;
            case HOLD_ALREADY_CLOSED -> LectureErrorCode.HOLD_ALREADY_CLOSED;
            case HOLD_TYPE_NOT_SUPPORTED -> LectureErrorCode.HOLD_TYPE_NOT_SUPPORTED;
            case HOLD_CHARGE_NOT_FOUND -> LectureErrorCode.HOLD_CHARGE_NOT_FOUND;
            case HOLD_STATE_INCONSISTENT -> LectureErrorCode.HOLD_STATE_INCONSISTENT;
            case CHARGE_PRINCIPAL_MISMATCH -> LectureErrorCode.CHARGE_PRINCIPAL_MISMATCH;
            case RELEASE_AMOUNT_EXCEEDS_HELD -> LectureErrorCode.RELEASE_AMOUNT_EXCEEDS_HELD;
            case PAYOUT_AMOUNT_EXCEEDS_HELD -> LectureErrorCode.PAYOUT_AMOUNT_EXCEEDS_HELD;
            case PAYOUT_TO_PAYER -> LectureErrorCode.PAYOUT_TO_PAYER;
            case PAYOUT_SOURCE_MISMATCH -> LectureErrorCode.PAYOUT_SOURCE_MISMATCH;
            case PAYOUT_ALREADY_SETTLED -> LectureErrorCode.PAYOUT_ALREADY_SETTLED;
            case PENALTY_BASE_EXCEEDS_HELD -> LectureErrorCode.PENALTY_BASE_EXCEEDS_HELD;
            case PENALTY_AMOUNT_ZERO -> LectureErrorCode.PENALTY_AMOUNT_ZERO;
            // 원격 호출이 처음 보는 오류 문자열을 받은 경우라 강의가 응답할 표현이 없다. 500으로 끝낸다
            case UNMAPPED -> throw new IllegalStateException("강의가 응답할 수 없는 billing 실패입니다: " + code);
        };
    }
}
