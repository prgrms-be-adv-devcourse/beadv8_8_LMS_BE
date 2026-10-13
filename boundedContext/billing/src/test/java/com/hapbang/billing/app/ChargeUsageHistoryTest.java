package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.billing.app.ChargeUsageHistory.Allocation;
import com.hapbang.billing.app.ChargeUsageHistory.Processing;
import com.hapbang.billing.app.ChargeUsageHistory.UsageRelease;
import com.hapbang.billing.app.ReleaseResult.ChargeRestoration;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.HoldDetailActionType;

// 충전 원금 반환의 역순(LIFO) 배분과, 반환 상세를 순서대로 재생해 처리 건별 충전 건 복원을 재구성하는 규칙을 DB 없이 검증한다.
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
    @DisplayName("지급의 충전 원금 소진 순서는 아직 정하지 않았으므로 충전 HP를 지급한 상세는 재생하지 않고 거절한다")
    void payoutWithChargeIsNotReplayable() {
        assertBillingError(() -> ChargeUsageHistory.replay(ALLOCATIONS, List.of(
                new Processing(10L, HoldDetailActionType.PAYOUT, 1_000L))), BillingErrorCode.HOLD_STATE_INCONSISTENT);

        // 수익 HP만 지급한 상세는 충전 원금 배분에 영향이 없다.
        ChargeUsageHistory history = ChargeUsageHistory.replay(ALLOCATIONS, List.of(
                new Processing(10L, HoldDetailActionType.PAYOUT, 0L)));
        assertThat(history.restorationsOf(10L)).isEmpty();
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
