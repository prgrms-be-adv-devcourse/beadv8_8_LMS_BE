package com.hapbang.billing.app;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
 *     이 목록은 pod 메모리에만 있어 pod가 다시 뜨면 사라진다. 그때는 failed 지표와 warn 로그로 안다.</li>
 * </ul>
 * 마지막 실행의 결과별 지갑 수는 {@code hapbang.billing.reconciliation.wallets{result}}(failed = 재시도 뒤에도 실패), 대상 지갑을
 * 모두 대사한(failed 0) 마지막 시각은 {@code hapbang.billing.reconciliation.last.success}(epoch 초)로 낸다. 한 지갑이라도 끝내 실패하면
 * last.success는 바뀌지 않는다. pod가 다시 뜨면 첫 실행 전까지 둘 다 0이다.
 */
@Slf4j
@Service
public class ChangedWalletReconciliationUseCase implements MeterBinder {

    static final String WALLETS_METRIC = "hapbang.billing.reconciliation.wallets";
    static final String LAST_SUCCESS_METRIC = "hapbang.billing.reconciliation.last.success";

    private final WalletRepository walletRepository;
    private final PointLedgerRepository pointLedgerRepository;
    private final WalletReconciliationUseCase walletReconciliationUseCase;
    private final Duration window;

    private final AtomicLong matchedWallets = new AtomicLong();
    private final AtomicLong mismatchedWallets = new AtomicLong();
    private final AtomicLong failedWallets = new AtomicLong();
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
    /** 지난 회차에서 재시도 뒤에도 실패해 다음 회차에 창과 관계없이 다시 대사할 지갑 */
    private final SortedSet<Long> carriedOver = new ConcurrentSkipListSet<>();

    public ChangedWalletReconciliationUseCase(WalletRepository walletRepository,
            PointLedgerRepository pointLedgerRepository, WalletReconciliationUseCase walletReconciliationUseCase,
            @Value("${hapbang.billing.reconciliation.window:72h}") Duration window) {
        this.walletRepository = walletRepository;
        this.pointLedgerRepository = pointLedgerRepository;
        this.walletReconciliationUseCase = walletReconciliationUseCase;
        this.window = window;
    }

    /** 기준 시각 이후 바뀐 지갑과 지난 회차에서 실패한 지갑을 대사하고 결과를 돌려준다 */
    public Result reconcileChanged(Instant now) {
        Instant since = now.minus(window);
        SortedSet<Long> walletIds = new TreeSet<>(walletRepository.findIdsUpdatedSince(since));
        walletIds.addAll(pointLedgerRepository.findWalletIdsRecordedSince(since));
        walletIds.addAll(carriedOver);

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
        carriedOver.clear();
        carriedOver.addAll(failed);

        Result result = new Result(since, tally.matched, List.copyOf(new TreeSet<>(tally.mismatched)), List.copyOf(failed));
        matchedWallets.set(result.matched());
        mismatchedWallets.set(result.mismatchedWalletIds().size());
        failedWallets.set(result.failedWalletIds().size());
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

    /** 다음 회차로 넘긴 지갑 ID(테스트·진단용) */
    SortedSet<Long> carriedOver() {
        return new TreeSet<>(carriedOver);
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
            log.error("잔액 대사 불일치 walletId={}, buckets=[{}]", walletId, mismatchedBuckets(result));
        }
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        wallets(registry, "matched", matchedWallets);
        wallets(registry, "mismatched", mismatchedWallets);
        wallets(registry, "failed", failedWallets);
        Gauge.builder(LAST_SUCCESS_METRIC, lastSuccessEpochSeconds, AtomicLong::get)
                .description("잔액 대사가 대상 지갑을 모두 대사한(재시도 뒤 failed 0) 마지막 시각(epoch 초). pod가 뜬 뒤 아직 없으면 0")
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
