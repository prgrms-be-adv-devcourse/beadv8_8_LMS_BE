package com.hapbang.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.hapbang.shared.billing.domain.SettlementSourceType;

// 강의 지급 예정 건의 상태 전이(중단·재개·취소)와 지급 가능 판단을 DB 없이 검증한다.
class SettlementTest {

    private static final Instant SCHEDULED_AT = Instant.parse("2026-11-12T15:00:00Z");

    @Test
    @DisplayName("강의 지급 예정 건은 주문 항목 원천·정상 지급·지급액 0·SCHEDULED로 만들어진다")
    void scheduledLecture() {
        Settlement settlement = scheduled();

        assertThat(settlement.getSourceType()).isEqualTo(SettlementSourceType.LECTURE_ORDER_ITEM);
        assertThat(settlement.getSourceId()).isEqualTo(5001L);
        assertThat(settlement.getPayoutType()).isEqualTo(PayoutType.NORMAL);
        assertThat(settlement.getGrossAmountHp()).isEqualTo(10_000L);
        assertThat(settlement.getPaidAmountHp()).isZero();
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.SCHEDULED);
        assertThat(settlement.getScheduledAt()).isEqualTo(SCHEDULED_AT);
        assertThat(settlement.getSettledAt()).isNull();
    }

    @Test
    @DisplayName("SCHEDULED → SUSPENDED → SCHEDULED로 오가도 예정 시각은 그대로이고, 같은 상태로의 재요청은 바꾸지 않는다")
    void suspendAndResume() {
        Settlement settlement = scheduled();

        assertThat(settlement.suspend()).isTrue();
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.SUSPENDED);
        assertThat(settlement.suspend()).isFalse();
        assertThat(settlement.resume()).isTrue();
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.SCHEDULED);
        assertThat(settlement.resume()).isFalse();
        assertThat(settlement.getScheduledAt()).isEqualTo(SCHEDULED_AT);
    }

    @Test
    @DisplayName("SCHEDULED·SUSPENDED는 CANCELLED로 끝낼 수 있고, CANCELLED에서는 중단·재개할 수 없다")
    void cancel() {
        Settlement fromScheduled = scheduled();
        Settlement fromSuspended = scheduled();
        fromSuspended.suspend();

        assertThat(fromScheduled.cancel()).isTrue();
        assertThat(fromSuspended.cancel()).isTrue();
        assertThat(fromScheduled.getStatus()).isEqualTo(SettlementStatus.CANCELLED);
        assertThat(fromSuspended.getStatus()).isEqualTo(SettlementStatus.CANCELLED);
        assertThat(fromScheduled.cancel()).isFalse();
        assertInvalidState(fromScheduled::resume);
        assertInvalidState(fromScheduled::suspend);
        assertThat(fromScheduled.getStatus()).isEqualTo(SettlementStatus.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = SettlementStatus.class, names = {"COMPLETED", "PROCESSING", "FAILED"})
    @DisplayName("처리 중·완료·실패 건은 중단·재개·취소할 수 없다(완료 후 환수는 별도 정책)")
    void noTransitionFromProcessedStates(SettlementStatus status) {
        Settlement settlement = withStatus(status);

        assertInvalidState(settlement::suspend);
        assertInvalidState(settlement::resume);
        assertInvalidState(settlement::cancel);
        assertThat(settlement.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("SCHEDULED이고 예정 시각이 지났을 때만 지급할 수 있고, 중단되면 예정 시각이 지나도 지급하지 않는다")
    void payable() {
        Settlement settlement = scheduled();

        assertThat(settlement.isPayableAt(SCHEDULED_AT.minusNanos(1))).isFalse();
        assertThat(settlement.isPayableAt(SCHEDULED_AT)).isTrue();
        settlement.suspend();
        assertThat(settlement.isPayableAt(SCHEDULED_AT.plusSeconds(86_400 * 30))).isFalse();
        settlement.resume();
        assertThat(settlement.isPayableAt(SCHEDULED_AT.plusSeconds(86_400 * 30))).isTrue();
        settlement.cancel();
        assertThat(settlement.isPayableAt(SCHEDULED_AT.plusSeconds(86_400 * 30))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = SettlementStatus.class, names = {"SUSPENDED", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED"})
    @DisplayName("SCHEDULED가 아니면 예정 시각과 관계없이 지급 가능이 아니다")
    void notPayableUnlessScheduled(SettlementStatus status) {
        assertThat(withStatus(status).isPayableAt(SCHEDULED_AT.plusSeconds(1))).isFalse();
    }

    private static Settlement scheduled() {
        return Settlement.scheduledLecture(20L, 2L, 3L, 5001L, 10_000L, "SETTLEMENT:LECTURE_ORDER_ITEM:5001",
                SCHEDULED_AT);
    }

    private static Settlement withStatus(SettlementStatus status) {
        return Settlement.builder().receiverUserId(20L).receiverWalletId(2L).pointHoldId(3L)
                .sourceType(SettlementSourceType.LECTURE_ORDER_ITEM).sourceId(5001L).payoutType(PayoutType.NORMAL)
                .grossAmountHp(10_000L).paidAmountHp(status == SettlementStatus.COMPLETED ? 10_000L : 0L)
                .status(status).idempotencyKey("SETTLEMENT:LECTURE_ORDER_ITEM:5001").scheduledAt(SCHEDULED_AT)
                .build();
    }

    private static void assertInvalidState(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BillingException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.SETTLEMENT_INVALID_STATE));
    }
}
