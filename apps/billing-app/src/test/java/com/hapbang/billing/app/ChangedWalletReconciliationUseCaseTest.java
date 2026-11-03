package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.out.PointLedgerRepository;
import com.hapbang.billing.out.WalletRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 변경된 지갑의 잔액 대사(#607, BD-139): 대상 합치기, 지갑 하나의 오류에도 계속, 결과 지표.
class ChangedWalletReconciliationUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-03T19:02:45Z");

    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final PointLedgerRepository pointLedgerRepository = mock(PointLedgerRepository.class);
    private final WalletReconciliationUseCase reconciliation = mock(WalletReconciliationUseCase.class);
    private final ChangedWalletReconciliationUseCase useCase = new ChangedWalletReconciliationUseCase(
            walletRepository, pointLedgerRepository, reconciliation, Duration.ofHours(72));

    @Test
    @DisplayName("수정된 지갑과 원장이 기록된 지갑을 합쳐 지갑마다 한 번씩 대사한다")
    void unionOfChangedWallets() {
        Instant since = NOW.minus(Duration.ofHours(72));
        when(walletRepository.findIdsUpdatedSince(since)).thenReturn(List.of(3L, 1L));
        when(pointLedgerRepository.findWalletIdsRecordedSince(since)).thenReturn(List.of(1L, 2L));
        when(reconciliation.reconcile(anyLong())).thenAnswer(call -> matched(call.getArgument(0)));

        ChangedWalletReconciliationUseCase.Result result = useCase.reconcileChanged(NOW);

        assertThat(result.since()).isEqualTo(since);
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.matched()).isEqualTo(3);
        verify(reconciliation, times(1)).reconcile(1L);
        verify(reconciliation, times(1)).reconcile(2L);
        verify(reconciliation, times(1)).reconcile(3L);
    }

    @Test
    @DisplayName("한 지갑에서 오류가 나도 그 지갑만 실패로 세고 나머지를 대사한다")
    void continuesAfterWalletFailure() {
        when(walletRepository.findIdsUpdatedSince(NOW.minus(Duration.ofHours(72)))).thenReturn(List.of(1L, 2L, 3L));
        when(reconciliation.reconcile(1L)).thenReturn(matched(1L));
        when(reconciliation.reconcile(2L)).thenThrow(new IllegalStateException("query timeout"));
        when(reconciliation.reconcile(3L)).thenReturn(mismatched(3L));

        ChangedWalletReconciliationUseCase.Result result = useCase.reconcileChanged(NOW);

        assertThat(result.matched()).isEqualTo(1);
        assertThat(result.failedWalletIds()).containsExactly(2L);
        assertThat(result.mismatchedWalletIds()).containsExactly(3L);
    }

    @Test
    @DisplayName("마지막 실행의 결과별 지갑 수와 끝까지 돈 시각을 지표로 낸다. 실행 전에는 0이다")
    void metrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        useCase.bindTo(registry);
        assertThat(wallets(registry, "mismatched")).isZero();
        assertThat(lastSuccess(registry)).isZero();

        when(walletRepository.findIdsUpdatedSince(NOW.minus(Duration.ofHours(72)))).thenReturn(List.of(1L, 2L, 3L));
        when(reconciliation.reconcile(1L)).thenReturn(matched(1L));
        when(reconciliation.reconcile(2L)).thenThrow(new IllegalStateException("query timeout"));
        when(reconciliation.reconcile(3L)).thenReturn(mismatched(3L));
        useCase.reconcileChanged(NOW);

        assertThat(wallets(registry, "matched")).isEqualTo(1);
        assertThat(wallets(registry, "mismatched")).isEqualTo(1);
        assertThat(wallets(registry, "failed")).isEqualTo(1);
        assertThat(lastSuccess(registry)).isEqualTo(NOW.getEpochSecond());

        // 다음 실행은 누적하지 않고 그 실행의 값으로 바꾼다
        Instant next = NOW.plus(Duration.ofDays(1));
        when(walletRepository.findIdsUpdatedSince(next.minus(Duration.ofHours(72)))).thenReturn(List.of(1L));
        useCase.reconcileChanged(next);

        assertThat(wallets(registry, "matched")).isEqualTo(1);
        assertThat(wallets(registry, "mismatched")).isZero();
        assertThat(wallets(registry, "failed")).isZero();
        assertThat(lastSuccess(registry)).isEqualTo(next.getEpochSecond());
    }

    private static double wallets(SimpleMeterRegistry registry, String result) {
        return registry.get(ChangedWalletReconciliationUseCase.WALLETS_METRIC).tag("result", result).gauge().value();
    }

    private static double lastSuccess(SimpleMeterRegistry registry) {
        return registry.get(ChangedWalletReconciliationUseCase.LAST_SUCCESS_METRIC).gauge().value();
    }

    private static WalletReconciliation matched(Long walletId) {
        return WalletReconciliation.of(walletId, List.of(WalletReconciliation.BucketReconciliation.of(
                PointType.CHARGE, BalanceBucket.AVAILABLE, 100L, 100L)));
    }

    private static WalletReconciliation mismatched(Long walletId) {
        return WalletReconciliation.of(walletId, List.of(WalletReconciliation.BucketReconciliation.of(
                PointType.CHARGE, BalanceBucket.AVAILABLE, 101L, 100L)));
    }
}
