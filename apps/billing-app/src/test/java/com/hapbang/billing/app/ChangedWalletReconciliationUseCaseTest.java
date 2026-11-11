package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import org.mockito.ArgumentCaptor;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.WalletReconciliationRun;
import com.hapbang.billing.out.PointLedgerRepository;
import com.hapbang.billing.out.WalletRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 변경된 지갑의 잔액 대사(#607, BD-139): 대상 합치기, 오류 지갑 재시도, 결과 지표, 창 설정.
// 실행 기록과 이월(#822, BD-146): 끝날 때 기록을 남기고, 다음 회차는 가장 최근 기록의 FAILED 지갑을 더한다. 기록 저장·읽기 실패는 대사를 멈추지 않는다.
class ChangedWalletReconciliationUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-03T19:02:45Z");
    private static final Duration WINDOW = Duration.ofHours(72);

    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final PointLedgerRepository pointLedgerRepository = mock(PointLedgerRepository.class);
    private final WalletReconciliationUseCase reconciliation = mock(WalletReconciliationUseCase.class);
    private final WalletReconciliationRunRecorder recorder = mock(WalletReconciliationRunRecorder.class);
    private final ChangedWalletReconciliationUseCase useCase = new ChangedWalletReconciliationUseCase(
            walletRepository, pointLedgerRepository, reconciliation, recorder, WINDOW);

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
    }

    @Test
    @DisplayName("끝나면 실행 시작 시각과 결과를 기록한다")
    void recordsRunAtEnd() {
        changedWallets(NOW, 1L, 2L, 3L);
        when(reconciliation.reconcile(1L)).thenReturn(matched(1L));
        when(reconciliation.reconcile(2L)).thenThrow(new IllegalStateException("query timeout"));
        when(reconciliation.reconcile(3L)).thenReturn(mismatched(3L));

        ChangedWalletReconciliationUseCase.Result result = useCase.reconcileChanged(NOW);

        ArgumentCaptor<ChangedWalletReconciliationUseCase.Result> recorded =
                ArgumentCaptor.forClass(ChangedWalletReconciliationUseCase.Result.class);
        verify(recorder).record(eq(NOW), any(Instant.class), recorded.capture());
        assertThat(recorded.getValue()).isEqualTo(result);
        assertThat(result.failedWalletIds()).containsExactly(2L);
    }

    @Test
    @DisplayName("가장 최근 실행 기록의 FAILED 지갑은 창 밖이어도 대사한다(pod 메모리가 아니라 기록에서 읽어 재시작에도 남는다)")
    void carriesFailedWalletsFromLastRecord() {
        changedWallets(NOW, 1L);
        when(recorder.lastFailedWalletIds()).thenReturn(List.of(2L));
        when(reconciliation.reconcile(1L)).thenReturn(matched(1L));
        when(reconciliation.reconcile(2L)).thenReturn(mismatched(2L));

        ChangedWalletReconciliationUseCase.Result result = useCase.reconcileChanged(NOW);

        assertThat(result.matched()).isEqualTo(1);
        assertThat(result.mismatchedWalletIds()).containsExactly(2L);
        verify(reconciliation, times(1)).reconcile(2L);
    }

    @Test
    @DisplayName("이월 지갑을 읽지 못해도 창 안의 지갑은 대사한다")
    void carryOverReadFailureDoesNotStopRun() {
        changedWallets(NOW, 1L);
        when(recorder.lastFailedWalletIds()).thenThrow(new IllegalStateException("connection refused"));
        when(reconciliation.reconcile(1L)).thenReturn(matched(1L));

        ChangedWalletReconciliationUseCase.Result result = useCase.reconcileChanged(NOW);

        assertThat(result.matched()).isEqualTo(1);
    }

    @Test
    @DisplayName("기록 저장이 실패해도 지표를 바꾸고 대사 결과를 돌려준다(찾은 불일치의 alert를 놓치지 않게)")
    void recordFailureStillUpdatesMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        useCase.bindTo(registry);
        changedWallets(NOW, 3L);
        when(reconciliation.reconcile(3L)).thenReturn(mismatched(3L));
        doThrow(new IllegalStateException("connection refused")).when(recorder).record(any(), any(), any());

        ChangedWalletReconciliationUseCase.Result result = useCase.reconcileChanged(NOW);

        assertThat(result.mismatchedWalletIds()).containsExactly(3L);
        assertThat(wallets(registry, "mismatched")).isEqualTo(1);
        assertThat(lastSuccess(registry)).isEqualTo(NOW.getEpochSecond());
    }

    @Test
    @DisplayName("이 인스턴스에서 대사가 이미 끝났으면 기동 시 복원이 더 오래된 기록으로 지표를 덮지 않는다")
    void restoreDoesNotOverwriteRunInThisInstance() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        useCase.bindTo(registry);
        changedWallets(NOW, 3L);
        when(reconciliation.reconcile(3L)).thenReturn(mismatched(3L));
        useCase.reconcileChanged(NOW);

        useCase.restoreFrom(WalletReconciliationRun.create(
                NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(4)), 9, 0, 0));
        useCase.restoreLastSuccess(NOW.minus(Duration.ofDays(1)));

        assertThat(wallets(registry, "mismatched")).isEqualTo(1);
        assertThat(wallets(registry, "matched")).isZero();
        assertThat(lastSuccess(registry)).isEqualTo(NOW.getEpochSecond());
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
                .withBean(WalletReconciliationRunRecorder.class, () -> recorder)
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

    static double wallets(SimpleMeterRegistry registry, String result) {
        return registry.get(ChangedWalletReconciliationUseCase.WALLETS_METRIC).tag("result", result).gauge().value();
    }

    static double lastSuccess(SimpleMeterRegistry registry) {
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
