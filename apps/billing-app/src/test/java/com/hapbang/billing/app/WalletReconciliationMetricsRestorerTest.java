package com.hapbang.billing.app;

import static com.hapbang.billing.app.ChangedWalletReconciliationUseCaseTest.lastSuccess;
import static com.hapbang.billing.app.ChangedWalletReconciliationUseCaseTest.wallets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.billing.domain.WalletReconciliationRun;
import com.hapbang.billing.out.PointLedgerRepository;
import com.hapbang.billing.out.WalletRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 기동할 때 잔액 대사 지표를 실행 기록으로 채운다 (#822, BD-146 (5)). 기록이 없으면 그대로 두고, DB를 읽지 못해도 기동을 막지 않는다.
class WalletReconciliationMetricsRestorerTest {

    private static final Instant STARTED = Instant.parse("2026-11-09T19:02:45Z");

    private final WalletReconciliationRunRecorder recorder = mock(WalletReconciliationRunRecorder.class);
    private final ChangedWalletReconciliationUseCase useCase = new ChangedWalletReconciliationUseCase(
            mock(WalletRepository.class), mock(PointLedgerRepository.class), mock(WalletReconciliationUseCase.class),
            recorder, Duration.ofHours(72));
    private final WalletReconciliationMetricsRestorer restorer = new WalletReconciliationMetricsRestorer(recorder, useCase);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    @DisplayName("가장 최근 실행의 결과별 지갑 수와 failed 0인 가장 최근 실행의 시작 시각으로 지표를 채운다")
    void restoresFromRecords() {
        useCase.bindTo(registry);
        Instant earlierSuccess = STARTED.minus(Duration.ofDays(1));
        when(recorder.latestRun()).thenReturn(Optional.of(
                WalletReconciliationRun.create(STARTED, STARTED.plusSeconds(3), STARTED.minus(Duration.ofHours(72)), 5, 2, 1)));
        when(recorder.lastSuccessStartedAt()).thenReturn(Optional.of(earlierSuccess));

        restorer.restore();

        assertThat(wallets(registry, "matched")).isEqualTo(5);
        assertThat(wallets(registry, "mismatched")).isEqualTo(2);
        assertThat(wallets(registry, "failed")).isEqualTo(1);
        assertThat(lastSuccess(registry)).isEqualTo(earlierSuccess.getEpochSecond());
    }

    @Test
    @DisplayName("실행 기록이 없으면 지표를 그대로 0으로 둔다")
    void noRecordsKeepsZero() {
        useCase.bindTo(registry);
        when(recorder.latestRun()).thenReturn(Optional.empty());
        when(recorder.lastSuccessStartedAt()).thenReturn(Optional.empty());

        restorer.restore();

        assertThat(wallets(registry, "matched")).isZero();
        assertThat(wallets(registry, "mismatched")).isZero();
        assertThat(wallets(registry, "failed")).isZero();
        assertThat(lastSuccess(registry)).isZero();
    }

    @Test
    @DisplayName("DB를 읽지 못해도 예외를 밖으로 내지 않아 기동을 막지 않고, 읽은 쪽은 채운다")
    void readFailureDoesNotStopBoot() {
        useCase.bindTo(registry);
        when(recorder.latestRun()).thenThrow(new IllegalStateException("connection refused"));
        when(recorder.lastSuccessStartedAt()).thenReturn(Optional.of(STARTED));

        assertThatCode(restorer::restore).doesNotThrowAnyException();
        assertThat(lastSuccess(registry)).isEqualTo(STARTED.getEpochSecond());

        when(recorder.lastSuccessStartedAt()).thenThrow(new IllegalStateException("connection refused"));
        assertThatCode(restorer::restore).doesNotThrowAnyException();
    }
}
