package com.hapbang.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

// 환전 송금 시도와 환전의 송금 상태 전이(#304)를 DB 없이 검증한다.
class ExchangePayoutTest {

    private static final Instant T0 = Instant.parse("2026-11-10T00:00:00Z");
    private static final String ID = "8f14e45f-ceea-4672-9b6f-0c4ea4c6e5d1";
    private static final Duration LIMIT = Duration.ofHours(24);

    @Test
    @DisplayName("새 시도는 PROCESSING이고 금액은 환전 예상 수령액, 확인 시각은 요청 시각, 자동 확인 0회다")
    void requested() {
        ExchangePayout payout = ExchangePayout.requested(approved(), 1, ID, T0, null);

        assertThat(payout.getStatus()).isEqualTo(ExchangePayoutStatus.PROCESSING);
        assertThat(payout.getAmountKrw()).isEqualTo(13_500L);
        assertThat(payout.getAttemptNo()).isEqualTo(1);
        assertThat(payout.getPayoutProvider()).isEqualTo(PayoutProvider.MOCK);
        assertThat(payout.getRequestedAt()).isEqualTo(T0);
        assertThat(payout.getLastCheckedAt()).isEqualTo(T0);
        assertThat(payout.getResultCheckCount()).isZero();
        assertThat(payout.getRequestedBy()).isNull();
        assertThat(payout.isInFlight()).isTrue();
        assertThat(ExchangePayout.requested(approved(), 2, ID, T0, 9L).getRequestedBy()).isEqualTo(9L);
    }

    @Test
    @DisplayName("결과 대기 시도는 PROCESSING ↔ UNKNOWN을 오가고 자동 확인마다 시각·횟수를 남기며 성공으로 끝날 수 있다")
    void transitions() {
        ExchangePayout payout = ExchangePayout.requested(approved(), 1, ID, T0, null);

        payout.unknown("NO_RESPONSE");
        assertThat(payout.getStatus()).isEqualTo(ExchangePayoutStatus.UNKNOWN);
        assertThat(payout.getFailureReason()).isEqualTo("NO_RESPONSE");
        payout.recordAutoCheck(T0.plusSeconds(60));
        payout.recordAutoCheck(T0.plusSeconds(120));
        assertThat(payout.getLastCheckedAt()).isEqualTo(T0.plusSeconds(120));
        assertThat(payout.getResultCheckCount()).isEqualTo(2);
        payout.processing();
        assertThat(payout.getStatus()).isEqualTo(ExchangePayoutStatus.PROCESSING);
        assertThat(payout.getFailureReason()).isNull();

        payout.succeed("MOCKTX-1", T0.plusSeconds(121));
        assertThat(payout.getStatus()).isEqualTo(ExchangePayoutStatus.COMPLETED);
        assertThat(payout.getPayoutTransactionId()).isEqualTo("MOCKTX-1");
        assertThat(payout.getCompletedAt()).isEqualTo(T0.plusSeconds(121));
        assertThat(payout.isUnresolved()).isFalse();
    }

    @Test
    @DisplayName("확정 실패는 실패 시각·코드를 남긴다")
    void fail() {
        ExchangePayout payout = ExchangePayout.requested(approved(), 2, ID, T0, null);

        payout.fail("MOCK_TRANSFER_REJECTED", T0.plusSeconds(1));

        assertThat(payout.getStatus()).isEqualTo(ExchangePayoutStatus.FAILED);
        assertThat(payout.getFailedAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(payout.getFailureReason()).isEqualTo("MOCK_TRANSFER_REJECTED");
    }

    @Test
    @DisplayName("자동 확인 기한은 최초 송금 요청 시각부터 24시간이다(경계 포함)")
    void autoCheckExpired() {
        ExchangePayout payout = ExchangePayout.requested(approved(), 1, ID, T0, null);
        payout.recordAutoCheck(T0.plus(LIMIT).minusSeconds(60));

        assertThat(payout.isAutoCheckExpired(T0.plus(LIMIT).minusNanos(1_000), LIMIT)).isFalse();
        assertThat(payout.isAutoCheckExpired(T0.plus(LIMIT), LIMIT)).isTrue();
    }

    @Test
    @DisplayName("운영자 확인: 처음 넘어간 시각만 남기고 자동 확인은 할 수 없다. 관리자 재확인 기록 후 성공·실패로 확정할 수 있다")
    void manualReview() {
        ExchangePayout payout = ExchangePayout.requested(approved(), 1, ID, T0, null);

        payout.requireManualReview("AUTO_CHECK_EXPIRED", T0.plusSeconds(10));
        payout.requireManualReview("REJECTED_400:X", T0.plusSeconds(20));

        assertThat(payout.getStatus()).isEqualTo(ExchangePayoutStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(payout.getManualReviewAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(payout.getFailureReason()).isEqualTo("REJECTED_400:X");
        assertThat(payout.isInFlight()).isFalse();
        assertThat(payout.isUnresolved()).isTrue();
        assertInconsistent(() -> payout.recordAutoCheck(T0));
        assertInconsistent(() -> payout.unknown("X"));
        assertInconsistent(payout::processing);

        payout.recordManualCheck(9L, T0.plusSeconds(30));
        assertThat(payout.getManualCheckedBy()).isEqualTo(9L);
        assertThat(payout.getManualCheckedAt()).isEqualTo(T0.plusSeconds(30));
        assertThat(payout.getLastCheckedAt()).isEqualTo(T0);
        assertThat(payout.getResultCheckCount()).isZero();

        payout.fail("BANK_RECORD_NOT_FOUND", T0.plusSeconds(31));
        assertThat(payout.getStatus()).isEqualTo(ExchangePayoutStatus.FAILED);
        assertThat(payout.getManualReviewAt()).isEqualTo(T0.plusSeconds(10));
    }

    @ParameterizedTest
    @EnumSource(value = ExchangePayoutStatus.class, names = {"COMPLETED", "FAILED", "PENDING"})
    @DisplayName("끝난 시도(성공·실패)는 다시 바꿀 수 없다")
    void closedAttempt(ExchangePayoutStatus status) {
        ExchangePayout payout = ExchangePayout.requested(approved(), 1, ID, T0, null);
        ReflectionTestUtils.setField(payout, "status", status);

        assertInconsistent(() -> payout.succeed("MOCKTX-1", T0));
        assertInconsistent(() -> payout.fail("X", T0));
        assertInconsistent(() -> payout.unknown("X"));
        assertInconsistent(payout::processing);
        assertInconsistent(() -> payout.requireManualReview("X", T0));
        assertInconsistent(() -> payout.recordAutoCheck(T0));
        assertInconsistent(() -> payout.recordManualCheck(9L, T0));
        assertThat(payout.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("거래 번호 없는 성공은 반영하지 않는다")
    void succeedWithoutTransaction() {
        ExchangePayout payout = ExchangePayout.requested(approved(), 1, ID, T0, null);

        assertInconsistent(() -> payout.succeed(" ", T0));
        assertThat(payout.getStatus()).isEqualTo(ExchangePayoutStatus.PROCESSING);
    }

    @Test
    @DisplayName("환전: 정산 예정 시각부터 송금할 수 있다(그 전은 아니다)")
    void payoutDue() {
        Exchange exchange = approved();

        assertThat(exchange.isPayoutDue(T0.minusNanos(1_000))).isFalse();
        assertThat(exchange.isPayoutDue(T0)).isTrue();
        assertThat(exchange.isPayoutDue(T0.plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("환전: APPROVED → PROCESSING → UNKNOWN → PROCESSING → COMPLETED, 승인 기록은 그대로다")
    void exchangeCompleted() {
        Exchange exchange = approved();

        exchange.startPayout();
        assertThat(exchange.getStatus()).isEqualTo(ExchangeStatus.PROCESSING);
        exchange.payoutUnknown();
        assertThat(exchange.getStatus()).isEqualTo(ExchangeStatus.UNKNOWN);
        exchange.payoutProcessing();
        exchange.completePayout(T0.plusSeconds(5));

        assertThat(exchange.getStatus()).isEqualTo(ExchangeStatus.COMPLETED);
        assertThat(exchange.getCompletedAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(exchange.getApprovedBy()).isEqualTo(9L);
        assertThatThrownBy(exchange::startPayout).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_NOT_PAYABLE);
        assertThatThrownBy(() -> exchange.failFinally(9L, "사유", T0)).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_PAYOUT_NOT_FINALIZABLE);
        assertInconsistent(() -> exchange.failPayout("X", T0));
    }

    @Test
    @DisplayName("환전: 확정 실패(FAILED) 뒤 다시 송금하면 PROCESSING이 되고 실패 시각·사유를 지운다")
    void exchangeRetryAfterFailure() {
        Exchange exchange = approved();
        exchange.startPayout();

        exchange.failPayout("MOCK_PAYOUT_FAILED:MOCK_TRANSFER_REJECTED", T0.plusSeconds(1));
        assertThat(exchange.getStatus()).isEqualTo(ExchangeStatus.FAILED);
        assertThat(exchange.getFailedAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(exchange.getFailureReason()).isEqualTo("MOCK_PAYOUT_FAILED:MOCK_TRANSFER_REJECTED");
        // 송금 단계 이후에는 회원 취소·관리자 심사를 할 수 없다
        assertThatThrownBy(() -> exchange.cancel(T0)).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_NOT_CANCELABLE);

        exchange.startPayout();
        assertThat(exchange.getStatus()).isEqualTo(ExchangeStatus.PROCESSING);
        assertThat(exchange.getFailedAt()).isNull();
        assertThat(exchange.getFailureReason()).isNull();
    }

    @Test
    @DisplayName("환전: FAILED만 최종 실패(FINAL_FAILED)로 종료하고, 이후 송금·결과 반영·다시 종료할 수 없다. 같은 관리자·사유만 멱등 대상이다")
    void exchangeFailFinally() {
        Exchange exchange = approved();
        exchange.startPayout();
        exchange.failPayout("MOCK_PAYOUT_FAILED:X", T0.plusSeconds(1));

        exchange.failFinally(9L, "  계좌 오류  ", T0.plusSeconds(2));

        assertThat(exchange.getStatus()).isEqualTo(ExchangeStatus.FINAL_FAILED);
        assertThat(exchange.getFinalFailedBy()).isEqualTo(9L);
        assertThat(exchange.getFinalFailedAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(exchange.getFinalFailureReason()).isEqualTo("계좌 오류");
        assertThat(exchange.getFailedAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(exchange.isFinalFailedBy(9L, "계좌 오류")).isTrue();
        assertThat(exchange.isFinalFailedBy(9L, "다른 사유")).isFalse();
        assertThat(exchange.isFinalFailedBy(8L, "계좌 오류")).isFalse();
        assertThatThrownBy(exchange::startPayout).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_NOT_PAYABLE);
        assertThatThrownBy(() -> exchange.failFinally(9L, "계좌 오류", T0)).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_PAYOUT_NOT_FINALIZABLE);
        assertInconsistent(() -> exchange.completePayout(T0));
        assertInconsistent(exchange::payoutUnknown);
    }

    @ParameterizedTest
    @EnumSource(value = ExchangeStatus.class, names = {"REQUESTED", "APPROVED", "REJECTED", "CANCELED", "PROCESSING",
            "UNKNOWN", "COMPLETED"})
    @DisplayName("환전: 확정 실패(FAILED)가 아니면 최종 실패로 종료할 수 없다(결과 불명·운영자 확인 포함)")
    void notFinalizable(ExchangeStatus status) {
        Exchange exchange = approved();
        ReflectionTestUtils.setField(exchange, "status", status);

        assertThatThrownBy(() -> exchange.failFinally(9L, "사유", T0)).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_PAYOUT_NOT_FINALIZABLE);
        assertThat(exchange.getStatus()).isEqualTo(status);
        assertThat(exchange.getFinalFailedBy()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("최종 실패 사유는 앞뒤 공백을 뺀 1~500자다")
    void finalFailureReason(String blank) {
        assertThatThrownBy(() -> Exchange.normalizeFinalFailureReason(blank)).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_FINAL_FAILURE_REASON_INVALID);
        assertThatThrownBy(() -> Exchange.normalizeFinalFailureReason("가".repeat(501))).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_FINAL_FAILURE_REASON_INVALID);
        assertThat(Exchange.normalizeFinalFailureReason(" " + "가".repeat(500) + " ")).hasSize(500);
    }

    @ParameterizedTest
    @EnumSource(value = ExchangeStatus.class, names = {"REQUESTED", "REJECTED", "CANCELED", "PROCESSING", "UNKNOWN",
            "COMPLETED", "FINAL_FAILED"})
    @DisplayName("환전: 승인(APPROVED)·확정 실패(FAILED)가 아니면 송금 시도를 시작할 수 없다")
    void notPayable(ExchangeStatus status) {
        Exchange exchange = approved();
        ReflectionTestUtils.setField(exchange, "status", status);

        assertThatThrownBy(exchange::startPayout).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_NOT_PAYABLE);
        assertThat(exchange.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = ExchangeStatus.class, names = {"REQUESTED", "APPROVED", "REJECTED", "CANCELED", "COMPLETED",
            "FAILED", "FINAL_FAILED"})
    @DisplayName("환전: 결과 대기(PROCESSING·UNKNOWN)가 아니면 송금 결과를 반영할 수 없다")
    void notInFlight(ExchangeStatus status) {
        Exchange exchange = approved();
        ReflectionTestUtils.setField(exchange, "status", status);

        assertInconsistent(exchange::payoutProcessing);
        assertInconsistent(exchange::payoutUnknown);
        assertInconsistent(() -> exchange.completePayout(T0));
        assertInconsistent(() -> exchange.failPayout("X", T0));
        assertThat(exchange.getStatus()).isEqualTo(status);
    }

    // 15,000 HP 신청(수수료 1,500, 수령액 13,500), 정산 예정 T0, 관리자 9 승인
    private static Exchange approved() {
        Exchange exchange = Exchange.requested(2L, 3L, 15_000L, "k", T0, T0.minusSeconds(86_400));
        exchange.approve(9L, T0.minusSeconds(3_600));
        return exchange;
    }

    private static void assertInconsistent(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(BillingException.class).extracting("errorCode")
                .isEqualTo(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
    }
}
