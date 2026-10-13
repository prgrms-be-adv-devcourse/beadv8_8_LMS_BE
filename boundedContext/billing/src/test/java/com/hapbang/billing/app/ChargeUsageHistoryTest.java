package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.billing.app.ChargeUsageHistory.Allocation;
import com.hapbang.billing.app.ChargeUsageHistory.Processing;
import com.hapbang.billing.app.ChargeUsageHistory.UsageConsumption;
import com.hapbang.billing.app.ChargeUsageHistory.UsageRelease;
import com.hapbang.billing.app.PayoutResult.ChargeConsumption;
import com.hapbang.billing.app.ReleaseResult.ChargeRestoration;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.HoldDetailActionType;

// 충전 원금 반환의 역순(LIFO)·지급의 FIFO 배분과, 반환·지급 상세를 순서대로 재생해 처리 건별 충전 건 복원·소진을 재구성하는 규칙을 DB 없이 검증한다.
class ChargeUsageHistoryTest {

    // 충전 #101에서 2,000, #102에서 3,000을 FIFO로 배분한 보류
    private static final List<Allocation> ALLOCATIONS = List.of(
            new Allocation(1L, 101L, 2_000L), new Allocation(2L, 102L, 3_000L));

    @Test
    @DisplayName("충전 HP 반환은 최초 배분의 역순으로, 나중에 배분한 충전 건부터 원금을 되돌린다")
    void releaseLifo() {
        ChargeUsageHistory history = ChargeUsageHistory.replay(ALLOCATIONS, List.of());

        List<UsageRelease> releases = history.release(4_000L);

        assertThat(releases).containsExactly(new UsageRelease(2L, 102L, 3_000L), new UsageRelease(1L, 101L, 1_000L));
        assertThat(history.matches(2L, 0L, 0L, 3_000L)).isTrue();
        assertThat(history.matches(1L, 1_000L, 0L, 1_000L)).isTrue();
    }

    @Test
    @DisplayName("이미 반환해 남은 보류가 없는 배분은 건너뛰고 그 앞 배분에서 이어서 반환한다")
    void releaseSkipsEmptyAllocation() {
        ChargeUsageHistory history = ChargeUsageHistory.replay(ALLOCATIONS, List.of());
        history.release(3_000L);

        assertThat(history.release(500L)).containsExactly(new UsageRelease(1L, 101L, 500L));
    }

    @Test
    @DisplayName("반환 상세를 순서대로 재생하면 각 반환이 어느 충전 건에서 얼마를 되돌렸는지 재구성된다")
    void replayReconstructsEachRelease() {
        ChargeUsageHistory history = ChargeUsageHistory.replay(ALLOCATIONS, List.of(
                new Processing(10L, HoldDetailActionType.RELEASE, 1_000L),
                new Processing(11L, HoldDetailActionType.RELEASE, 0L),
                new Processing(12L, HoldDetailActionType.RELEASE, 3_000L)));

        assertThat(history.restorationsOf(10L)).containsExactly(new ChargeRestoration(102L, 1_000L));
        assertThat(history.restorationsOf(11L)).isEmpty();
        assertThat(history.restorationsOf(12L)).containsExactly(
                new ChargeRestoration(102L, 2_000L), new ChargeRestoration(101L, 1_000L));
        assertThat(history.matches(1L, 1_000L, 0L, 1_000L)).isTrue();
        assertThat(history.matches(2L, 0L, 0L, 3_000L)).isTrue();
        // DB 누적값이 재생 결과와 다르면 맞지 않는다고 판단한다.
        assertThat(history.matches(1L, 0L, 0L, 2_000L)).isFalse();
    }

    @Test
    @DisplayName("남은 보류 합계보다 많은 충전 HP 반환은 HOLD_STATE_INCONSISTENT로 거절한다")
    void releaseMoreThanHeld() {
        assertBillingError(() -> ChargeUsageHistory.replay(ALLOCATIONS, List.of(
                new Processing(10L, HoldDetailActionType.RELEASE, 5_001L))), BillingErrorCode.HOLD_STATE_INCONSISTENT);
        assertBillingError(() -> ChargeUsageHistory.replay(ALLOCATIONS, List.of()).release(5_001L),
                BillingErrorCode.HOLD_STATE_INCONSISTENT);
    }

    @Test
    @DisplayName("충전 HP 지급은 최초 배분 순서(FIFO)로, 먼저 배분한 충전 건부터 소진하고 소진액으로 기록한다")
    void consumeFifo() {
        ChargeUsageHistory history = ChargeUsageHistory.replay(ALLOCATIONS, List.of());

        assertThat(history.consume(2_500L)).containsExactly(
                new UsageConsumption(1L, 101L, 2_000L), new UsageConsumption(2L, 102L, 500L));
        assertThat(history.matches(1L, 0L, 2_000L, 0L)).isTrue();
        assertThat(history.matches(2L, 2_500L, 500L, 0L)).isTrue();
    }

    @Test
    @DisplayName("반환(역순)·지급(FIFO)이 섞인 상세를 순서대로 재생하면 처리 건별 충전 건 복원·소진이 재구성된다")
    void replayMixedReleaseAndPayout() {
        ChargeUsageHistory history = ChargeUsageHistory.replay(ALLOCATIONS, List.of(
                new Processing(10L, HoldDetailActionType.RELEASE, 1_000L),
                new Processing(11L, HoldDetailActionType.PAYOUT, 2_500L),
                new Processing(12L, HoldDetailActionType.PAYOUT, 0L),
                new Processing(13L, HoldDetailActionType.RELEASE, 1_500L)));

        assertThat(history.restorationsOf(10L)).containsExactly(new ChargeRestoration(102L, 1_000L));
        assertThat(history.consumptionsOf(11L)).containsExactly(
                new ChargeConsumption(101L, 2_000L), new ChargeConsumption(102L, 500L));
        assertThat(history.consumptionsOf(12L)).isEmpty();
        assertThat(history.restorationsOf(13L)).containsExactly(new ChargeRestoration(102L, 1_500L));
        // 배분액 = 남은 보류 + 소진 + 반환
        assertThat(history.matches(1L, 0L, 2_000L, 0L)).isTrue();
        assertThat(history.matches(2L, 0L, 500L, 2_500L)).isTrue();
        // 지급 상세의 복원 내역, 반환 상세의 소진 내역은 없다.
        assertBillingError(() -> history.restorationsOf(11L), BillingErrorCode.HOLD_STATE_INCONSISTENT);
        assertBillingError(() -> history.consumptionsOf(10L), BillingErrorCode.HOLD_STATE_INCONSISTENT);
    }

    @Test
    @DisplayName("남은 보류 합계보다 많은 충전 HP 지급은 HOLD_STATE_INCONSISTENT로 거절한다")
    void consumeMoreThanHeld() {
        assertBillingError(() -> ChargeUsageHistory.replay(ALLOCATIONS, List.of(
                new Processing(10L, HoldDetailActionType.RELEASE, 1_000L),
                new Processing(11L, HoldDetailActionType.PAYOUT, 4_001L))), BillingErrorCode.HOLD_STATE_INCONSISTENT);
    }

    @Test
    @DisplayName("재생하지 않은 상세의 복원 내역은 HOLD_STATE_INCONSISTENT로 거절한다")
    void unknownDetail() {
        assertBillingError(() -> ChargeUsageHistory.replay(ALLOCATIONS, List.of()).restorationsOf(99L),
                BillingErrorCode.HOLD_STATE_INCONSISTENT);
    }

    private static void assertBillingError(Runnable action, BillingErrorCode errorCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BillingException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
