package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.out.PointLedgerRepository;
import com.hapbang.billing.out.WalletRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 변경된 지갑의 잔액 대사(#607, BD-139): 대상 합치기, 오류 지갑 재시도·다음 회차 이월, 결과 지표, 창 설정.
class ChangedWalletReconciliationUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-03T19:02:45Z");
    private static final Duration WINDOW = Duration.ofHours(72);

    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final PointLedgerRepository pointLedgerRepository = mock(PointLedgerRepository.class);
    private final WalletReconciliationUseCase reconciliation = mock(WalletReconciliationUseCase.class);
    private final ChangedWalletReconciliationUseCase useCase = new ChangedWalletReconciliationUseCase(
            walletRepository, pointLedgerRepository, reconciliation, WINDOW);

    @Test
    @DisplayName("수정된 지갑과 원장이 기록된 지갑을 합쳐 지갑마다 한 번씩 대사한다")
    void unionOfChangedWallets() {
        Instant since = NOW.minus(WINDOW);
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
    @DisplayName("한 지갑의 오류가 나머지를 막지 않고, 오류 지갑은 끝에 한 번 더 시도해 순간 오류를 흡수한다")
    void retriesFailedWalletOnceAtEnd() {
        changedWallets(NOW, 1L, 2L, 3L);
        when(reconciliation.reconcile(1L)).thenReturn(matched(1L));
        when(reconciliation.reconcile(2L)).thenThrow(new IllegalStateException("query timeout")).thenReturn(matched(2L));
        when(reconciliation.reconcile(3L)).thenReturn(mismatched(3L));

        ChangedWalletReconciliationUseCase.Result result = useCase.reconcileChanged(NOW);

        assertThat(result.matched()).isEqualTo(2);
        assertThat(result.mismatchedWalletIds()).containsExactly(3L);
        assertThat(result.failedWalletIds()).isEmpty();
        verify(reconciliation, times(2)).reconcile(2L);
        assertThat(useCase.carriedOver()).isEmpty();
    }

    @Test
    @DisplayName("재시도에도 실패한 지갑은 다음 회차에 창 밖이어도 다시 대사하고, 성공하면 목록에서 빠진다")
    void carriesFailedWalletToNextRun() {
        changedWallets(NOW, 1L, 2L);
        when(reconciliation.reconcile(1L)).thenReturn(matched(1L));
        when(reconciliation.reconcile(2L)).thenThrow(new IllegalStateException("query timeout"));

        ChangedWalletReconciliationUseCase.Result first = useCase.reconcileChanged(NOW);

        assertThat(first.failedWalletIds()).containsExactly(2L);
        verify(reconciliation, times(2)).reconcile(2L);
        assertThat(useCase.carriedOver()).containsExactly(2L);

        // 다음 날: 지갑 2는 창 밖으로 나갔다(조회 결과에 없음). 그래도 이어서 대사한다
        Instant next = NOW.plus(Duration.ofDays(1));
        doReturn(mismatched(2L)).when(reconciliation).reconcile(2L);

        ChangedWalletReconciliationUseCase.Result second = useCase.reconcileChanged(next);

        assertThat(second.mismatchedWalletIds()).containsExactly(2L);
        assertThat(second.failedWalletIds()).isEmpty();
        assertThat(useCase.carriedOver()).isEmpty();

        // 그다음 날에는 다시 대사하지 않는다
        useCase.reconcileChanged(next.plus(Duration.ofDays(1)));
        verify(reconciliation, times(3)).reconcile(2L);
    }

    @Test
    @DisplayName("결과별 지갑 수는 마지막 실행 값으로 바꾸고, 마지막 성공 시각은 모든 대상을 대사했을 때만 바꾼다. 실행 전에는 0이다")
    void metrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        useCase.bindTo(registry);
        assertThat(wallets(registry, "mismatched")).isZero();
        assertThat(lastSuccess(registry)).isZero();

        changedWallets(NOW, 1L, 3L);
        when(reconciliation.reconcile(1L)).thenReturn(matched(1L));
        when(reconciliation.reconcile(3L)).thenReturn(mismatched(3L));
        useCase.reconcileChanged(NOW);

        assertThat(wallets(registry, "matched")).isEqualTo(1);
        assertThat(wallets(registry, "mismatched")).isEqualTo(1);
        assertThat(wallets(registry, "failed")).isZero();
        // 불일치가 있어도 모두 대사했으면 성공이다(불일치는 mismatched로 본다)
        assertThat(lastSuccess(registry)).isEqualTo(NOW.getEpochSecond());

        // 다음 실행은 누적하지 않고 그 실행의 값으로 바꾼다. 한 지갑이라도 끝내 실패하면 마지막 성공 시각은 그대로다
        Instant next = NOW.plus(Duration.ofDays(1));
        changedWallets(next, 1L, 2L);
        when(reconciliation.reconcile(2L)).thenThrow(new IllegalStateException("query timeout"));
        useCase.reconcileChanged(next);

        assertThat(wallets(registry, "matched")).isEqualTo(1);
        assertThat(wallets(registry, "mismatched")).isZero();
        assertThat(wallets(registry, "failed")).isEqualTo(1);
        assertThat(lastSuccess(registry)).isEqualTo(NOW.getEpochSecond());
    }

    @Test
    @DisplayName("모든 지갑이 실패하면 failed가 대상 수이고 마지막 성공 시각은 바뀌지 않는다(DB 장애를 정상으로 보이지 않게)")
    void allWalletsFailed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        useCase.bindTo(registry);
        changedWallets(NOW, 1L, 2L, 3L);
        when(reconciliation.reconcile(anyLong())).thenThrow(new IllegalStateException("connection refused"));

        ChangedWalletReconciliationUseCase.Result result = useCase.reconcileChanged(NOW);

        assertThat(result.failedWalletIds()).containsExactly(1L, 2L, 3L);
        assertThat(result.matched()).isZero();
        assertThat(wallets(registry, "failed")).isEqualTo(3);
        assertThat(wallets(registry, "matched")).isZero();
        assertThat(lastSuccess(registry)).isZero();
        assertThat(useCase.carriedOver()).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("대상 지갑이 없으면 모두 대사한 것으로 보고 마지막 성공 시각을 바꾼다")
    void noTargetIsSuccess() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        useCase.bindTo(registry);

        useCase.reconcileChanged(NOW);

        assertThat(lastSuccess(registry)).isEqualTo(NOW.getEpochSecond());
        verify(reconciliation, never()).reconcile(anyLong());
    }

    @Test
    @DisplayName("창 설정(hapbang.billing.reconciliation.window)을 바꾸면 그 값으로 기준 시각을 정하고, 없으면 72시간이다")
    void windowProperty() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withBean(WalletRepository.class, () -> walletRepository)
                .withBean(PointLedgerRepository.class, () -> pointLedgerRepository)
                .withBean(WalletReconciliationUseCase.class, () -> reconciliation)
                .withUserConfiguration(ChangedWalletReconciliationUseCase.class);

        runner.withPropertyValues("hapbang.billing.reconciliation.window=24h").run(context -> {
            ChangedWalletReconciliationUseCase.Result result =
                    context.getBean(ChangedWalletReconciliationUseCase.class).reconcileChanged(NOW);
            assertThat(result.since()).isEqualTo(NOW.minus(Duration.ofHours(24)));
            verify(walletRepository).findIdsUpdatedSince(NOW.minus(Duration.ofHours(24)));
            verify(pointLedgerRepository).findWalletIdsRecordedSince(NOW.minus(Duration.ofHours(24)));
        });
        runner.run(context -> assertThat(context.getBean(ChangedWalletReconciliationUseCase.class)
                .reconcileChanged(NOW).since()).isEqualTo(NOW.minus(WINDOW)));
    }

    // 기준 시각 이후 수정된 지갑을 정한다(원장 쪽은 비어 있다).
    private void changedWallets(Instant now, Long... walletIds) {
        when(walletRepository.findIdsUpdatedSince(now.minus(WINDOW))).thenReturn(List.of(walletIds));
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
