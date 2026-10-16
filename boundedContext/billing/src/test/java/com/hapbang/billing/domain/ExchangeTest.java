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
    @CsvSource({"1000, 100", "15000, 1500", "1009, 100", "1999, 199", "9223372036854775000, 922337203685477500"})
    @DisplayName("수수료는 금액 × 10%이고 원 미만은 버린다")
    void feeOf(long amountHp, long expectedFee) {
        assertThat(Exchange.feeOf(amountHp)).isEqualTo(expectedFee);
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
    @ValueSource(longs = {0L, -1_000L, 999L, 1_009L, 1_500L, 1_999L, 10_001L})
    @DisplayName("1,000 미만이거나 1,000 단위가 아니면 EXCHANGE_AMOUNT_INVALID")
    void invalidAmount(long amountHp) {
        assertThatThrownBy(() -> Exchange.requested(2L, 3L, amountHp, "k", T0, T0))
                .isInstanceOf(BillingException.class)
                .extracting("errorCode").isEqualTo(BillingErrorCode.EXCHANGE_AMOUNT_INVALID);
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
