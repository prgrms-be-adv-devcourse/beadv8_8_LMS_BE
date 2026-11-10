package com.hapbang.billing.app;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.hapbang.billing.domain.WalletReconciliationRun;
import com.hapbang.billing.out.PointLedgerRepository;
import com.hapbang.billing.out.WalletRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;

/**
 * 최근에 바뀐 지갑만 골라 잔액 대사를 한다 (#607, BD-139).
 * <ul>
 *     <li>대상은 기준 시각(지금 - 시간 창, 기본 72시간) 이후 지갑이 수정됐거나({@code wallet.updated_at}) 원장이 기록된
 *     ({@code point_ledger.created_at}) 지갑이다. 마지막 실행 시점은 저장하지 않고, 창이 하루보다 길어 몇 회차를 건너뛰어도 놓치지 않는다.
 *     창보다 오래 바뀌지 않은 지갑은 대사하지 않는다.</li>
 *     <li>지갑마다 {@link WalletReconciliationUseCase#reconcile}(한 스냅숏)을 따로 부른다. 불일치를 고치지 않는다(BD-20).</li>
 *     <li>한 지갑의 오류는 나머지를 막지 않는다. 오류가 난 지갑은 전체를 한 번 돈 뒤 한 번 더 대사하고, 그래도 실패하면
 *     다음 회차 대상에 창과 관계없이 더한다(창은 지갑의 마지막 변경 기준이라, 창 끝에 가까운 지갑은 다음 회차 창 밖으로 나간다).
 *     이월 대상은 가장 최근 실행 기록의 FAILED 지갑이다(#822, BD-146). 읽지 못하면 이월 없이 대사한다.</li>
 *     <li>끝나면 실행 기록을 남긴다({@link WalletReconciliationRunRecorder}). 저장이 실패해도 지표는 바꾸고 결과를 돌려준다.</li>
 * </ul>
 * 마지막 실행의 결과별 지갑 수는 {@code hapbang.billing.reconciliation.wallets{result}}(failed = 재시도 뒤에도 실패), 대상 지갑을
 * 모두 대사한(failed 0) 마지막 시각은 {@code hapbang.billing.reconciliation.last.success}(epoch 초)로 낸다. 한 지갑이라도 끝내 실패하면
 * last.success는 바뀌지 않는다. pod가 다시 뜨면 {@link WalletReconciliationMetricsRestorer}가 기동할 때 실행 기록으로 채운다.
 */
@Slf4j
@Service
public class ChangedWalletReconciliationUseCase implements MeterBinder {

    static final String WALLETS_METRIC = "hapbang.billing.reconciliation.wallets";
    static final String LAST_SUCCESS_METRIC = "hapbang.billing.reconciliation.last.success";

    private final WalletRepository walletRepository;
    private final PointLedgerRepository pointLedgerRepository;
    private final WalletReconciliationUseCase walletReconciliationUseCase;
    private final WalletReconciliationRunRecorder recorder;
    private final Duration window;

    private final AtomicLong matchedWallets = new AtomicLong();
    private final AtomicLong mismatchedWallets = new AtomicLong();
    private final AtomicLong failedWallets = new AtomicLong();
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
    /** 이 인스턴스에서 대사가 끝났는지. 기동 시 복원이 그 결과를 오래된 기록으로 덮지 않게 한다 (guarded by this) */
    private boolean completedRunInThisInstance;

    public ChangedWalletReconciliationUseCase(WalletRepository walletRepository,
            PointLedgerRepository pointLedgerRepository, WalletReconciliationUseCase walletReconciliationUseCase,
            WalletReconciliationRunRecorder recorder,
            @Value("${hapbang.billing.reconciliation.window:72h}") Duration window) {
        this.walletRepository = walletRepository;
        this.pointLedgerRepository = pointLedgerRepository;
        this.walletReconciliationUseCase = walletReconciliationUseCase;
        this.recorder = recorder;
        this.window = window;
    }

    /** 기준 시각 이후 바뀐 지갑과 지난 회차에서 실패한 지갑을 대사하고 결과를 돌려준다 */
    public Result reconcileChanged(Instant now) {
        Instant since = now.minus(window);
        SortedSet<Long> walletIds = new TreeSet<>(walletRepository.findIdsUpdatedSince(since));
        walletIds.addAll(pointLedgerRepository.findWalletIdsRecordedSince(since));
        walletIds.addAll(carriedOver());

        Tally tally = new Tally();
        List<Long> retry = new ArrayList<>();
        for (Long walletId : walletIds) {
            try {
                tally.add(walletId, walletReconciliationUseCase.reconcile(walletId));
            } catch (RuntimeException e) {
                retry.add(walletId);
                log.warn("잔액 대사 실패, 끝에 한 번 더 시도 walletId={}", walletId, e);
            }
        }
        List<Long> failed = new ArrayList<>();
        for (Long walletId : retry) {
            try {
                tally.add(walletId, walletReconciliationUseCase.reconcile(walletId));
            } catch (RuntimeException e) {
                failed.add(walletId);
                log.warn("잔액 대사 재시도 실패, 다음 회차에 다시 대사 walletId={}", walletId, e);
            }
        }
        Result result = new Result(since, tally.matched, List.copyOf(new TreeSet<>(tally.mismatched)), List.copyOf(failed));
        record(now, result);
        synchronized (this) {
            completedRunInThisInstance = true;
            matchedWallets.set(result.matched());
            mismatchedWallets.set(result.mismatchedWalletIds().size());
            failedWallets.set(result.failedWalletIds().size());
        }
        if (failed.isEmpty()) {
            lastSuccessEpochSeconds.set(now.getEpochSecond());
        }
        checkClosedWallets();
        log.info("잔액 대사 완료 since={}, total={}, matched={}, mismatched={}, retried={}, failed={}", since,
                result.total(), result.matched(), result.mismatchedWalletIds().size(), retry.size(),
                result.failedWalletIds().size());
        return result;
    }

    // 탈퇴로 닫혔는데 쓸 수 있는 HP가 남은 지갑 ID를 warn 로그로 남긴다 (#623, BD-141). 닫힌 뒤 들어온 반환·지급은 환불·환전할 수 없어
    // 회원이 복구하기 전까지 묶인다. 대사 대상(최근 변경)과 관계없이 전체를 본다. 지표는 수집 때마다 세는 ClosedWalletBalanceMetrics가 낸다.
    private void checkClosedWallets() {
        try {
            List<Long> walletIds = walletRepository.findClosedIdsWithBalance();
            if (!walletIds.isEmpty()) {
                log.warn("잔액이 남은 닫힌 지갑 count={}, walletIds={}", walletIds.size(), walletIds);
            }
        } catch (RuntimeException e) {
            log.warn("잔액이 남은 닫힌 지갑을 세지 못했다", e);
        }
    }

    // 가장 최근 실행 기록에서 재시도 뒤에도 실패한 지갑. 읽지 못하면 이월 없이 대사한다(창 안의 지갑은 그대로 대사된다).
    private List<Long> carriedOver() {
        try {
            return recorder.lastFailedWalletIds();
        } catch (RuntimeException e) {
            log.warn("잔액 대사 이월 지갑을 읽지 못해 이월 없이 대사한다", e);
            return List.of();
        }
    }

    // 실행 기록을 남긴다. 저장이 실패해도 지표는 바꾼다(찾은 불일치의 alert를 놓치지 않게). 이번 FAILED 지갑은 다음 회차에 이월되지 않을 수 있다.
    private void record(Instant startedAt, Result result) {
        try {
            recorder.record(startedAt, Instant.now(), result);
        } catch (RuntimeException e) {
            log.error("[BILLING_RECONCILIATION_RECORD_FAILED] 잔액 대사 실행 기록을 저장하지 못했다 since={}, mismatchedWalletIds={}, failedWalletIds={}",
                    result.since(), result.mismatchedWalletIds(), result.failedWalletIds(), e);
        }
    }

    // 기동할 때 실행 기록으로 지표를 채운다(WalletReconciliationMetricsRestorer, BD-146 (5)).
    // 이 인스턴스에서 대사가 이미 끝났으면 그 값이 더 새롭다. 복원이 DB를 읽은 뒤 값을 넣기 전에 대사가 끝나는 경우에도 덮지 않는다.
    void restoreFrom(WalletReconciliationRun run) {
        synchronized (this) {
            if (completedRunInThisInstance) {
                return;
            }
            matchedWallets.set(run.getMatchedCount());
            mismatchedWallets.set(run.getMismatchedCount());
            failedWallets.set(run.getFailedCount());
        }
    }

    // 마지막 성공 시각은 뒤로 가지 않는다(이 인스턴스의 대사가 먼저 갱신했으면 그대로 둔다).
    void restoreLastSuccess(Instant startedAt) {
        lastSuccessEpochSeconds.accumulateAndGet(startedAt.getEpochSecond(), Math::max);
    }

    // 대사가 끝난 지갑을 일치·불일치로 나눠 센다. 불일치는 error 로그를 남긴다.
    private static final class Tally {
        private int matched;
        private final List<Long> mismatched = new ArrayList<>();

        void add(Long walletId, WalletReconciliation result) {
            if (result.matched()) {
                matched++;
                return;
            }
            mismatched.add(walletId);
            log.error("잔액 대사 불일치 walletId={}, buckets=[{}], invariants=[{}]", walletId, mismatchedBuckets(result),
                    mismatchedInvariants(result));
        }
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        wallets(registry, "matched", matchedWallets);
        wallets(registry, "mismatched", mismatchedWallets);
        wallets(registry, "failed", failedWallets);
        Gauge.builder(LAST_SUCCESS_METRIC, lastSuccessEpochSeconds, AtomicLong::get)
                .description("잔액 대사가 대상 지갑을 모두 대사한(재시도 뒤 failed 0) 마지막 시각(epoch 초). 기동할 때 실행 기록으로 채우고, 기록이 없으면 0")
                .baseUnit("seconds")
                .register(registry);
    }

    private static void wallets(MeterRegistry registry, String result, AtomicLong value) {
        Gauge.builder(WALLETS_METRIC, value, AtomicLong::get)
                .description("마지막 잔액 대사의 결과별 지갑 수")
                .tag("result", result)
                .register(registry);
    }

    // 차이가 있는 버킷만 "HP 종류/버킷 wallet=캐시 ledger=원장 diff=차이"로 나열한다.
    private static String mismatchedBuckets(WalletReconciliation result) {
        return result.buckets().stream()
                .filter(bucket -> !bucket.matched())
                .map(bucket -> "%s/%s wallet=%d ledger=%d diff=%d".formatted(bucket.pointType(),
                        bucket.balanceBucket(), bucket.walletHp(), bucket.ledgerHp(), bucket.differenceHp()))
                .collect(Collectors.joining(", "));
    }

    // 맞지 않는 불변식만 "이름 왼쪽값 관계 오른쪽값"으로 나열한다(프로모션 지갑). 없으면 빈 문자열이다.
    private static String mismatchedInvariants(WalletReconciliation result) {
        return result.invariants().stream()
                .filter(check -> !check.matched())
                .map(check -> "%s %d %s %d".formatted(check.name(), check.leftHp(), check.relation(), check.rightHp()))
                .collect(Collectors.joining(", "));
    }

    /**
     * 한 번의 대사 결과.
     *
     * @param since               대상 기준 시각(이 시각 이후 바뀐 지갑을 봤다)
     * @param matched             일치한 지갑 수
     * @param mismatchedWalletIds 불일치 지갑 ID(오름차순)
     * @param failedWalletIds     재시도 뒤에도 대사하지 못한 지갑 ID(오름차순). 다음 회차에 다시 대사한다
     */
    public record Result(Instant since, int matched, List<Long> mismatchedWalletIds, List<Long> failedWalletIds) {

        /** 대상 지갑 수 */
        public int total() {
            return matched + mismatchedWalletIds.size() + failedWalletIds.size();
        }
    }
}
