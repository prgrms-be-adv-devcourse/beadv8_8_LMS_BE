package com.hapbang.billing.app;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.domain.WalletReconciliationResult;
import com.hapbang.billing.domain.WalletReconciliationRun;
import com.hapbang.billing.domain.WalletReconciliationRunWallet;
import com.hapbang.billing.out.WalletReconciliationRunRepository;
import com.hapbang.billing.out.WalletReconciliationRunWalletRepository;

import lombok.RequiredArgsConstructor;

/**
 * 잔액 대사 실행 기록의 저장·조회 (#822, BD-146).
 * <ul>
 *     <li>회차가 끝나면 실행 1행과 불일치·실패 지갑 행을 한 트랜잭션에 남긴다. 일치한 지갑은 남기지 않는다.</li>
 *     <li>다음 회차 이월 대상은 가장 최근 실행의 FAILED 지갑이다(pod 메모리가 아니라 기록이 원본이라 재시작에도 남는다).</li>
 *     <li>기동할 때 지표 복원은 가장 최근 실행과 failed 0인 가장 최근 실행을 쓴다.</li>
 * </ul>
 * 저장·조회 실패의 처리(대사·기동을 멈추지 않음)는 호출하는 쪽이 정한다.
 */
@Component
@RequiredArgsConstructor
public class WalletReconciliationRunRecorder {

    private final WalletReconciliationRunRepository runRepository;
    private final WalletReconciliationRunWalletRepository runWalletRepository;

    @Transactional
    public void record(Instant startedAt, Instant finishedAt, ChangedWalletReconciliationUseCase.Result result) {
        WalletReconciliationRun run = runRepository.save(WalletReconciliationRun.create(startedAt, finishedAt,
                result.since(), result.matched(), result.mismatchedWalletIds().size(),
                result.failedWalletIds().size()));
        runWalletRepository.saveAll(Stream.concat(
                        result.mismatchedWalletIds().stream()
                                .map(walletId -> WalletReconciliationRunWallet.create(run.getId(), walletId,
                                        WalletReconciliationResult.MISMATCHED)),
                        result.failedWalletIds().stream()
                                .map(walletId -> WalletReconciliationRunWallet.create(run.getId(), walletId,
                                        WalletReconciliationResult.FAILED)))
                .toList());
    }

    /** 가장 최근 실행에서 재시도 뒤에도 실패한 지갑 ID(오름차순). 기록이 없으면 비어 있다 */
    @Transactional(readOnly = true)
    public List<Long> lastFailedWalletIds() {
        return runRepository.findFirstByOrderByStartedAtDescIdDesc()
                .filter(run -> run.getFailedCount() > 0)
                .map(run -> runWalletRepository.findWalletIds(run.getId(), WalletReconciliationResult.FAILED))
                .orElse(List.of());
    }

    @Transactional(readOnly = true)
    public Optional<WalletReconciliationRun> latestRun() {
        return runRepository.findFirstByOrderByStartedAtDescIdDesc();
    }

    /** failed가 0인 가장 최근 실행의 시작 시각 */
    @Transactional(readOnly = true)
    public Optional<Instant> lastSuccessStartedAt() {
        return runRepository.findFirstByFailedCountOrderByStartedAtDescIdDesc(0)
                .map(WalletReconciliationRun::getStartedAt);
    }
}
