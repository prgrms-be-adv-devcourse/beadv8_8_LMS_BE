package com.hapbang.billing.app;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 기동할 때 잔액 대사 지표를 실행 기록으로 채운다 (#822, BD-146 (5)).
 * <p>
 * 지표는 앱 메모리에 있어, 새벽 대사에서 불일치·실패 지갑이 나와도 그날 pod가 다시 뜨면 다음 대사 전까지 0이 된다. 그러면 alert
 * "지갑 잔액 대사 불일치·실패"가 고치지 않았는데도 복구 통보를 보내고, "대사가 돌지 않음"은 오래 산 pod만 볼 수 있다. 그래서 가장 최근 실행으로
 * 결과별 지갑 수를, failed 0인 가장 최근 실행의 시작 시각으로 마지막 성공 시각을 채운다. 기록이 없으면 그대로(0) 둔다.
 * <p>
 * 두 지표는 따로 실패한다. DB를 읽지 못해도 기동은 막지 않는다.
 */
@Slf4j
@Component
public class WalletReconciliationMetricsRestorer {

    private final WalletReconciliationRunRecorder recorder;
    private final ChangedWalletReconciliationUseCase useCase;

    public WalletReconciliationMetricsRestorer(WalletReconciliationRunRecorder recorder,
            ChangedWalletReconciliationUseCase useCase) {
        this.recorder = recorder;
        this.useCase = useCase;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        restore();
    }

    public void restore() {
        restoreWallets();
        restoreLastSuccess();
    }

    private void restoreWallets() {
        try {
            recorder.latestRun().ifPresent(run -> {
                useCase.restoreFrom(run);
                log.info("잔액 대사 지표를 마지막 실행으로 채웠다 runId={}, startedAt={}, matched={}, mismatched={}, failed={}",
                        run.getId(), run.getStartedAt(), run.getMatchedCount(), run.getMismatchedCount(),
                        run.getFailedCount());
            });
        } catch (RuntimeException e) {
            log.warn("잔액 대사 결과별 지갑 수를 DB에서 채우지 못해 다음 대사까지 0으로 낸다", e);
        }
    }

    private void restoreLastSuccess() {
        try {
            recorder.lastSuccessStartedAt().ifPresent(startedAt -> {
                useCase.restoreLastSuccess(startedAt);
                log.info("잔액 대사 마지막 성공 시각을 채웠다 startedAt={}", startedAt);
            });
        } catch (RuntimeException e) {
            log.warn("잔액 대사 마지막 성공 시각을 DB에서 채우지 못해 다음 성공까지 0으로 낸다", e);
        }
    }
}
