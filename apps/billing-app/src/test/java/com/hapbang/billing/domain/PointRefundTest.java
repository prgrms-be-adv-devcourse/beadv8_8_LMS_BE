package com.hapbang.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 개별 환불(PG 취소 건) 상태 전이 (#260 확정 정책 ②③): CANCELED는 PG에 요청하지 않은 건만, UNKNOWN 한도와 멱등키 안전 기간 판단
class PointRefundTest {

    private static final Instant T0 = Instant.parse("2026-10-15T00:00:00Z");

    @Test
    @DisplayName("PG에 요청하지 않은 PENDING 건만 CANCELED로 끝낼 수 있다")
    void cancel_onlyPending() {
        PointRefund pending = refund();
        pending.cancel("STOPPED", T0);
        assertThat(pending.getStatus()).isEqualTo(PointRefundStatus.CANCELED);

        PointRefund started = refund();
        started.startAttempt(T0);
        assertThatThrownBy(() -> started.cancel("STOPPED", T0)).isInstanceOf(IllegalStateException.class);

        PointRefund unknown = refund();
        unknown.startAttempt(T0);
        unknown.markUnknown("TIMEOUT", T0);
        assertThatThrownBy(() -> unknown.cancel("STOPPED", T0)).isInstanceOf(IllegalStateException.class);

        PointRefund succeeded = refund();
        succeeded.startAttempt(T0);
        succeeded.succeed("tx", T0);
        assertThatThrownBy(() -> succeeded.cancel("STOPPED", T0)).isInstanceOf(IllegalStateException.class);
        assertThat(succeeded.getStatus()).isEqualTo(PointRefundStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("최초 PG 요청 시각과 최초 UNKNOWN 시각은 따로 남고, 최초 UNKNOWN 시각은 바뀌지 않는다")
    void timestamps() {
        PointRefund refund = refund();
        refund.startAttempt(T0);
        refund.markUnknown("TIMEOUT", T0.plusSeconds(10));
        refund.recordCancelRetry(T0.plusSeconds(600));
        refund.markUnknown("TIMEOUT", T0.plusSeconds(700));

        assertThat(refund.getFirstCancelRequestedAt()).isEqualTo(T0);
        assertThat(refund.getLastCancelRequestedAt()).isEqualTo(T0.plusSeconds(600));
        assertThat(refund.getCancelRequestCount()).isEqualTo(2);
        assertThat(refund.getUnknownSince()).isEqualTo(T0.plusSeconds(10));
    }

    @Test
    @DisplayName("UNKNOWN 한도: 24시간 경계·재확인 12회에 도달하면 원인을 돌려주고, UNKNOWN이 아니면 적용하지 않는다")
    void unknownLimit() {
        Duration day = Duration.ofHours(24);
        PointRefund refund = refund();
        refund.startAttempt(T0);
        assertThat(refund.unknownLimitReached(T0.plus(Duration.ofDays(3)), day, 12)).isNull();

        refund.markUnknown("TIMEOUT", T0);
        assertThat(refund.unknownLimitReached(T0.plus(day).minusNanos(1000), day, 12)).isNull();
        assertThat(refund.unknownLimitReached(T0.plus(day), day, 12))
                .isEqualTo(RefundManualReviewReason.UNKNOWN_TIME_LIMIT);
        for (int i = 0; i < 11; i++) {
            refund.recordResultCheck(T0);
        }
        assertThat(refund.unknownLimitReached(T0, day, 12)).isNull();
        refund.recordResultCheck(T0);
        assertThat(refund.unknownLimitReached(T0, day, 12)).isEqualTo(RefundManualReviewReason.UNKNOWN_CHECK_LIMIT);
    }

    @Test
    @DisplayName("멱등키 안전 기간은 최초 PG 요청 시각 기준이고 경계에서 끝난 것으로 본다")
    void idempotencyWindow() {
        Duration window = Duration.ofDays(14);
        PointRefund refund = refund();
        assertThat(refund.isIdempotencyWindowOver(T0, window)).isTrue();

        refund.startAttempt(T0);
        refund.recordCancelRetry(T0.plus(Duration.ofDays(10)));
        assertThat(refund.isIdempotencyWindowOver(T0.plus(window).minusNanos(1000), window)).isFalse();
        assertThat(refund.isIdempotencyWindowOver(T0.plus(window), window)).isTrue();
    }

    @Test
    @DisplayName("운영자 확인은 결과를 기다리는 건에만, 원인·시각과 함께 남는다")
    void manualReview() {
        PointRefund refund = refund();
        assertThatThrownBy(() -> refund.requireManualReview(RefundManualReviewReason.PG_REFUND_NOT_POSSIBLE, "r", T0))
                .isInstanceOf(IllegalStateException.class);
        refund.startAttempt(T0);
        refund.requireManualReview(RefundManualReviewReason.PG_REFUND_NOT_POSSIBLE, "r", T0);

        assertThat(refund.getStatus()).isEqualTo(PointRefundStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(refund.getManualReviewReason()).isEqualTo(RefundManualReviewReason.PG_REFUND_NOT_POSSIBLE);
        assertThat(refund.getManualReviewAt()).isEqualTo(T0);
        assertThatThrownBy(() -> refund.cancel("STOPPED", T0)).isInstanceOf(IllegalStateException.class);
    }

    private static PointRefund refund() {
        PointRefundRequest request = PointRefundRequest.requested(1L, 1_000L, "key", T0);
        return PointRefund.pending(request, 1L, 1_000L, "hapbang-refund-cancel-1-1", T0);
    }
}
