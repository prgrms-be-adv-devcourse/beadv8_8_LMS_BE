package com.hapbang.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

// 환전 신청의 수수료 계산·금액 검증·상태 전이(#285)를 DB 없이 검증한다.
class ExchangeTest {

    private static final Instant T0 = Instant.parse("2026-10-16T00:00:00Z");

    @ParameterizedTest
    @CsvSource({"1, 0, 1", "2, 1, 1", "9, 8, 1", "10, 9, 1", "11, 9, 2", "1234, 1110, 124", "1000, 900, 100",
            "15000, 13500, 1500", "1009, 908, 101", "1999, 1799, 200",
            "9223372036854775000, 8301034833169297500, 922337203685477500"})
    @DisplayName("송금액은 금액 × 90%에서 원 미만을 버리고, 수수료는 금액 − 송금액이라 항상 1 HP 이상이다 (#453)")
    void payoutAndFee(long amountHp, long expectedPayout, long expectedFee) {
        assertThat(Exchange.payoutOf(amountHp)).isEqualTo(expectedPayout);
        assertThat(Exchange.feeOf(amountHp)).isEqualTo(expectedFee);
        assertThat(Exchange.payoutOf(amountHp) + Exchange.feeOf(amountHp)).isEqualTo(amountHp);
    }

    @Test
    @DisplayName("신청은 REQUESTED이고 수수료율·수수료·예상 수령액을 신청 시 확정한다")
    void requested() {
        Exchange exchange = Exchange.requested(2L, 3L, 15_000L, "EXCHANGE:3:k", T0.plusSeconds(60), T0);

        assertThat(exchange.getStatus()).isEqualTo(ExchangeStatus.REQUESTED);
        assertThat(exchange.getFeeRate()).isEqualByComparingTo(new BigDecimal("0.1"));
        assertThat(exchange.getFeeAmountHp()).isEqualTo(1_500L);
        assertThat(exchange.getPayoutAmountKrw()).isEqualTo(13_500L);
        assertThat(exchange.getFeeAmountHp() + exchange.getPayoutAmountKrw()).isEqualTo(15_000L);
        assertThat(exchange.getPointHoldId()).isNull();
        assertThat(exchange.getCanceledAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, -1_000L})
    @DisplayName("1 HP보다 작으면 EXCHANGE_AMOUNT_TOO_SMALL (#453)")
    void tooSmallAmount(long amountHp) {
        assertThatThrownBy(() -> Exchange.requested(2L, 3L, amountHp, "k", T0, T0))
                .isInstanceOf(BillingException.class)
                .extracting("errorCode").isEqualTo(BillingErrorCode.EXCHANGE_AMOUNT_TOO_SMALL);
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 2L, 999L, 1_009L, 10_001L})
    @DisplayName("1 HP 단위의 어떤 금액이든 신청할 수 있다 (#453)")
    void anyAmountFromOne(long amountHp) {
        Exchange exchange = Exchange.requested(2L, 3L, amountHp, "k", T0, T0);

        assertThat(exchange.getRequestedAmountHp()).isEqualTo(amountHp);
        assertThat(exchange.getPayoutAmountKrw()).isEqualTo(Exchange.payoutOf(amountHp));
        assertThat(exchange.getFeeAmountHp()).isPositive();
    }

    @Test
    @DisplayName("송금액 0원(신청 1 HP) 환전만 승인 상태에서 은행 송금 없이 완료할 수 있다 (#453)")
    void completeWithoutTransfer() {
        Exchange zero = Exchange.requested(2L, 3L, 1L, "k", T0, T0);
        assertThat(zero.hasNoTransfer()).isTrue();
        assertThatThrownBy(() -> zero.completeWithoutTransfer(T0)).isInstanceOf(BillingException.class)
                .extracting("errorCode").isEqualTo(BillingErrorCode.EXCHANGE_NOT_PAYABLE);
        ReflectionTestUtils.setField(zero, "status", ExchangeStatus.APPROVED);

        zero.completeWithoutTransfer(T0.plusSeconds(1));

        assertThat(zero.getStatus()).isEqualTo(ExchangeStatus.COMPLETED);
        assertThat(zero.getCompletedAt()).isEqualTo(T0.plusSeconds(1));

        Exchange two = Exchange.requested(2L, 3L, 2L, "k", T0, T0);
        ReflectionTestUtils.setField(two, "status", ExchangeStatus.APPROVED);
        assertThat(two.hasNoTransfer()).isFalse();
        assertThatThrownBy(() -> two.completeWithoutTransfer(T0)).isInstanceOf(BillingException.class)
                .extracting("errorCode").isEqualTo(BillingErrorCode.EXCHANGE_NOT_PAYABLE);
    }

    @Test
    @DisplayName("REQUESTED만 취소할 수 있고, 취소하면 CANCELED와 취소 시각이 남는다")
    void cancel() {
        Exchange exchange = Exchange.requested(2L, 3L, 1_000L, "k", T0, T0);
        exchange.attachHold(7L);

        exchange.cancel(T0.plusSeconds(1));

        assertThat(exchange.isCanceled()).isTrue();
        assertThat(exchange.getCanceledAt()).isEqualTo(T0.plusSeconds(1));
        assertThatThrownBy(() -> exchange.cancel(T0.plusSeconds(2)))
                .extracting("errorCode").isEqualTo(BillingErrorCode.EXCHANGE_NOT_CANCELABLE);
    }

    @ParameterizedTest
    @EnumSource(value = ExchangeStatus.class, names = {"PROCESSING", "COMPLETED", "FAILED", "UNKNOWN"})
    @DisplayName("송금 단계 이후(승인 뒤) 상태는 취소할 수 없다")
    void notCancelableAfterRequested(ExchangeStatus status) {
        Exchange exchange = Exchange.requested(2L, 3L, 1_000L, "k", T0, T0);
        ReflectionTestUtils.setField(exchange, "status", status);

        assertThatThrownBy(() -> exchange.cancel(T0))
                .extracting("errorCode").isEqualTo(BillingErrorCode.EXCHANGE_NOT_CANCELABLE);
        assertThat(exchange.getStatus()).isEqualTo(status);
        assertThat(exchange.getCanceledAt()).isNull();
    }

    @Test
    @DisplayName("관리자 승인: REQUESTED → APPROVED, 관리자·시각을 남기고 정산 예정일은 그대로다. 같은 관리자만 멱등 대상이다")
    void approve() {
        Exchange exchange = Exchange.requested(2L, 3L, 1_000L, "k", T0.plusSeconds(60), T0);

        exchange.approve(9L, T0.plusSeconds(5));

        assertThat(exchange.getStatus()).isEqualTo(ExchangeStatus.APPROVED);
        assertThat(exchange.getApprovedBy()).isEqualTo(9L);
        assertThat(exchange.getApprovedAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(exchange.getPayoutScheduledAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(exchange.isApprovedBy(9L)).isTrue();
        assertThat(exchange.isApprovedBy(8L)).isFalse();
        assertThatThrownBy(() -> exchange.approve(9L, T0)).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_ALREADY_REVIEWED);
        assertThatThrownBy(() -> exchange.reject(9L, "사유", T0)).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_ALREADY_REVIEWED);
        // 승인 후에는 회원이 취소할 수 없다
        assertThatThrownBy(() -> exchange.cancel(T0)).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_NOT_CANCELABLE);
    }

    @Test
    @DisplayName("승인 + 회차 확정(#305): 정한 정산 예정 시각을 저장한다. 회차가 승인 시각보다 뒤가 아니면 거절하고, 승인 대기가 아니면 409")
    void approveWithRound() {
        Exchange exchange = Exchange.requested(2L, 3L, 1_000L, "k", T0.plusSeconds(60), T0);

        exchange.approve(9L, T0.plusSeconds(70), T0.plusSeconds(86_400));

        assertThat(exchange.getStatus()).isEqualTo(ExchangeStatus.APPROVED);
        assertThat(exchange.getApprovedAt()).isEqualTo(T0.plusSeconds(70));
        assertThat(exchange.getPayoutScheduledAt()).isEqualTo(T0.plusSeconds(86_400));
        assertThatThrownBy(() -> exchange.approve(9L, T0, T0.plusSeconds(86_400))).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_ALREADY_REVIEWED);

        Exchange pastRound = Exchange.requested(2L, 3L, 1_000L, "k", T0.plusSeconds(60), T0);
        assertThatThrownBy(() -> pastRound.approve(9L, T0.plusSeconds(70), T0.plusSeconds(70))).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
        assertThatThrownBy(() -> pastRound.approve(9L, T0.plusSeconds(70), null)).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
        assertThat(pastRound.getStatus()).isEqualTo(ExchangeStatus.REQUESTED);
        assertThat(pastRound.getPayoutScheduledAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    @DisplayName("관리자 거절: REQUESTED → REJECTED, 사유는 앞뒤 공백을 빼고 저장한다. 같은 관리자·같은 사유만 멱등 대상이다")
    void reject() {
        Exchange exchange = Exchange.requested(2L, 3L, 1_000L, "k", T0, T0);

        exchange.reject(9L, "  계좌 불일치  ", T0.plusSeconds(5));

        assertThat(exchange.getStatus()).isEqualTo(ExchangeStatus.REJECTED);
        assertThat(exchange.getRejectedBy()).isEqualTo(9L);
        assertThat(exchange.getRejectedAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(exchange.getRejectionReason()).isEqualTo("계좌 불일치");
        assertThat(exchange.isRejectedBy(9L, "계좌 불일치")).isTrue();
        assertThat(exchange.isRejectedBy(9L, "다른 사유")).isFalse();
        assertThat(exchange.isRejectedBy(8L, "계좌 불일치")).isFalse();
        assertThatThrownBy(() -> exchange.approve(9L, T0)).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_ALREADY_REVIEWED);
        assertThatThrownBy(() -> exchange.cancel(T0)).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_NOT_CANCELABLE);
    }

    @ParameterizedTest
    @EnumSource(value = ExchangeStatus.class, names = {"CANCELED", "PROCESSING", "COMPLETED", "FAILED", "UNKNOWN"})
    @DisplayName("취소·송금 단계 신청은 승인·거절할 수 없다")
    void notReviewable(ExchangeStatus status) {
        Exchange exchange = Exchange.requested(2L, 3L, 1_000L, "k", T0, T0);
        ReflectionTestUtils.setField(exchange, "status", status);

        assertThatThrownBy(() -> exchange.approve(9L, T0)).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_ALREADY_REVIEWED);
        assertThatThrownBy(() -> exchange.reject(9L, "사유", T0)).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_ALREADY_REVIEWED);
        assertThat(exchange.getApprovedBy()).isNull();
        assertThat(exchange.getRejectedBy()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    @DisplayName("거절 사유가 비었거나 공백뿐이면 EXCHANGE_REJECTION_REASON_INVALID")
    void blankRejectionReason(String reason) {
        assertThatThrownBy(() -> Exchange.normalizeRejectionReason(reason)).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_REJECTION_REASON_INVALID);
        assertThatThrownBy(() -> Exchange.normalizeRejectionReason(null)).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_REJECTION_REASON_INVALID);
    }

    @Test
    @DisplayName("거절 사유는 앞뒤 공백을 뺀 길이로 1~500자다")
    void rejectionReasonLength() {
        assertThat(Exchange.normalizeRejectionReason("  " + "가".repeat(500) + "  ")).hasSize(500);
        assertThatThrownBy(() -> Exchange.normalizeRejectionReason("가".repeat(501))).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_REJECTION_REASON_INVALID);
    }

    @Test
    @DisplayName("보류는 한 번만, REQUESTED에서만 연결한다")
    void attachHoldOnce() {
        Exchange exchange = Exchange.requested(2L, 3L, 1_000L, "k", T0, T0);
        exchange.attachHold(7L);

        assertThatThrownBy(() -> exchange.attachHold(8L))
                .extracting("errorCode").isEqualTo(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
        assertThat(exchange.getPointHoldId()).isEqualTo(7L);
    }
}
