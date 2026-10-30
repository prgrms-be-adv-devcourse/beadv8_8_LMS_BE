package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.shared.billing.domain.BillingFailureCode;

// Billing 오류 코드 전체를 shared BillingFailureCode(내부 API Client가 쓰는 코드)로 바꾸는 대조 (#273, docs/billing/CONTEXT.md 1.6).
// 내부 API 네 경로(보류·반환·지급·위약금 정산)에서 응답할 수 있는 코드는 같은 이름의 BillingFailureCode로, 나오지 않는 코드는 null로 둔다.
// switch에 default가 없으므로 Billing에 오류 코드가 늘면 컴파일되지 않아, 내부 API 계약에 넣을지 반드시 정하게 된다.
class BillingFailureCodeContractTest {

    @ParameterizedTest
    @EnumSource(BillingErrorCode.class)
    @DisplayName("내부 API에서 나오는 Billing 오류 코드는 같은 이름의 BillingFailureCode로 바뀐다")
    void sameName(BillingErrorCode code) {
        BillingFailureCode failure = toFailureCode(code);
        if (failure != null) {
            assertThat(failure.name()).isEqualTo(code.name());
            assertThat(BillingFailureCode.from(code.name())).isEqualTo(failure);
        }
    }

    @Test
    @DisplayName("BillingFailureCode는 UNMAPPED를 빼고 모두 어떤 Billing 오류 코드에서 바뀌어 나온다")
    void everyFailureCodeIsReachable() {
        Set<BillingFailureCode> mapped = Arrays.stream(BillingErrorCode.values())
                .map(BillingFailureCodeContractTest::toFailureCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(BillingFailureCode.class)));

        assertThat(mapped).isEqualTo(EnumSet.complementOf(EnumSet.of(BillingFailureCode.UNMAPPED)));
    }

    // 내부 API 네 경로에서 응답할 수 있으면 BillingFailureCode, 아니면 null. default를 두지 않는다.
    private static BillingFailureCode toFailureCode(BillingErrorCode code) {
        return switch (code) {
            // 지갑·잔액
            case WALLET_NOT_FOUND -> BillingFailureCode.WALLET_NOT_FOUND;
            case INSUFFICIENT_BALANCE -> BillingFailureCode.INSUFFICIENT_BALANCE;
            case BALANCE_OVERFLOW -> BillingFailureCode.BALANCE_OVERFLOW;
            case INVALID_POINT_AMOUNT -> BillingFailureCode.INVALID_POINT_AMOUNT;
            // 멱등
            case IDEMPOTENCY_KEY_CONFLICT -> BillingFailureCode.IDEMPOTENCY_KEY_CONFLICT;
            // 보류
            case HOLD_NOT_FOUND -> BillingFailureCode.HOLD_NOT_FOUND;
            case HOLD_OWNER_MISMATCH -> BillingFailureCode.HOLD_OWNER_MISMATCH;
            case HOLD_ALREADY_CLOSED -> BillingFailureCode.HOLD_ALREADY_CLOSED;
            case HOLD_TYPE_NOT_SUPPORTED -> BillingFailureCode.HOLD_TYPE_NOT_SUPPORTED;
            case HOLD_CHARGE_NOT_FOUND -> BillingFailureCode.HOLD_CHARGE_NOT_FOUND;
            case HOLD_STATE_INCONSISTENT -> BillingFailureCode.HOLD_STATE_INCONSISTENT;
            case CHARGE_PRINCIPAL_MISMATCH -> BillingFailureCode.CHARGE_PRINCIPAL_MISMATCH;
            // 반환·지급
            case RELEASE_AMOUNT_EXCEEDS_HELD -> BillingFailureCode.RELEASE_AMOUNT_EXCEEDS_HELD;
            case PAYOUT_AMOUNT_EXCEEDS_HELD -> BillingFailureCode.PAYOUT_AMOUNT_EXCEEDS_HELD;
            case PAYOUT_TO_PAYER -> BillingFailureCode.PAYOUT_TO_PAYER;
            case PAYOUT_SOURCE_MISMATCH -> BillingFailureCode.PAYOUT_SOURCE_MISMATCH;
            case PAYOUT_ALREADY_SETTLED -> BillingFailureCode.PAYOUT_ALREADY_SETTLED;
            // 위약금 정산 (출연)
            case PENALTY_BASE_EXCEEDS_HELD -> BillingFailureCode.PENALTY_BASE_EXCEEDS_HELD;
            case PENALTY_AMOUNT_ZERO -> BillingFailureCode.PENALTY_AMOUNT_ZERO;
            // 락 대기 초과
            case HP_LOCK_TIMEOUT -> BillingFailureCode.HP_LOCK_TIMEOUT;
            // 내부 API에서 나오지 않는다: 역분개, 확정 차감(노출하지 않음), 충전, 환불, 환전, 수익 조회, Backfill, Mock 은행
            case ORIGINAL_LEDGER_NOT_FOUND, INVALID_REVERSAL, CAPTURE_AMOUNT_EXCEEDS_HELD,
                    CHARGE_UNAVAILABLE, CHARGE_AMOUNT_INVALID, CHARGE_NOT_FOUND, CHARGE_AMOUNT_MISMATCH,
                    CHARGE_PAYMENT_KEY_CONFLICT, CHARGE_ALREADY_FAILED, CHARGE_PAYMENT_REJECTED, CHARGE_STATE_INCONSISTENT,
                    REFUND_UNAVAILABLE, REFUND_AMOUNT_TOO_SMALL, REFUND_IDEMPOTENCY_KEY_INVALID, REFUND_NOT_FOUND,
                    REFUND_NOT_CANCELABLE, REFUND_STATE_INCONSISTENT,
                    EXCHANGE_UNAVAILABLE, EXCHANGE_AMOUNT_TOO_SMALL, EXCHANGE_IDEMPOTENCY_KEY_INVALID, EXCHANGE_NOT_FOUND,
                    EXCHANGE_NOT_CANCELABLE, EXCHANGE_ALREADY_REVIEWED, EXCHANGE_APPLICANT_NOT_SYNCED,
                    EXCHANGE_APPLICANT_NOT_ELIGIBLE, EXCHANGE_REJECTION_REASON_INVALID, EXCHANGE_NOT_PAYABLE,
                    EXCHANGE_PAYOUT_NOT_DUE, EXCHANGE_PAYOUT_UNAVAILABLE, EXCHANGE_PAYOUT_NOT_RETRYABLE,
                    EXCHANGE_PAYOUT_NOT_FINALIZABLE, EXCHANGE_PAYOUT_NOT_CHECKABLE, EXCHANGE_FINAL_FAILURE_REASON_INVALID,
                    EXCHANGE_STATE_INCONSISTENT, EXCHANGE_ACCOUNT_UNREADABLE,
                    EARNING_SOURCE_UNAVAILABLE, EARNING_SOURCE_REJECTED, EARNING_PAGE_TOO_DEEP,
                    MEMBER_SOURCE_UNAVAILABLE, MEMBER_SOURCE_REJECTED, BACKFILL_ALREADY_RUNNING,
                    MOCK_PAYOUT_FORBIDDEN, MOCK_PAYOUT_INVALID_REQUEST, MOCK_PAYOUT_IDEMPOTENCY_CONFLICT,
                    MOCK_PAYOUT_NOT_FOUND, MOCK_PAYOUT_UNAVAILABLE, MOCK_PAYOUT_SERVER_ERROR -> null;
        };
    }
}
