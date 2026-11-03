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
 *     <li>한 지갑의 오류는 그 지갑만 실패로 세고 나머지를 계속한다. 다음 회차의 창 안에서 다시 대사된다.</li>
 * </ul>
 * 마지막 실행의 결과별 지갑 수는 {@code hapbang.billing.reconciliation.wallets{result}}, 끝까지 돈 마지막 시각은
 * {@code hapbang.billing.reconciliation.last.success}(epoch 초)로 낸다. pod가 다시 뜨면 첫 실행 전까지 둘 다 0이다.
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

    public ChangedWalletReconciliationUseCase(WalletRepository walletRepository,
            PointLedgerRepository pointLedgerRepository, WalletReconciliationUseCase walletReconciliationUseCase,
            @Value("${hapbang.billing.reconciliation.window:72h}") Duration window) {
        this.walletRepository = walletRepository;
        this.pointLedgerRepository = pointLedgerRepository;
        this.walletReconciliationUseCase = walletReconciliationUseCase;
        this.window = window;
    }

    /** 기준 시각 이후 바뀐 지갑을 대사하고 결과를 돌려준다 */
    public Result reconcileChanged(Instant now) {
        Instant since = now.minus(window);
        SortedSet<Long> walletIds = new TreeSet<>(walletRepository.findIdsUpdatedSince(since));
        walletIds.addAll(pointLedgerRepository.findWalletIdsRecordedSince(since));

        int matched = 0;
        List<Long> mismatched = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        for (Long walletId : walletIds) {
            try {
                WalletReconciliation result = walletReconciliationUseCase.reconcile(walletId);
                if (result.matched()) {
                    matched++;
                } else {
                    mismatched.add(walletId);
                    log.error("잔액 대사 불일치 walletId={}, buckets=[{}]", walletId, mismatchedBuckets(result));
                }
            } catch (RuntimeException e) {
                failed.add(walletId);
                log.warn("잔액 대사 실패 walletId={}", walletId, e);
            }
        }

        Result result = new Result(since, matched, List.copyOf(mismatched), List.copyOf(failed));
        matchedWallets.set(result.matched());
        mismatchedWallets.set(result.mismatchedWalletIds().size());
        failedWallets.set(result.failedWalletIds().size());
        lastSuccessEpochSeconds.set(now.getEpochSecond());
        log.info("잔액 대사 완료 since={}, total={}, matched={}, mismatched={}, failed={}", since, result.total(),
                result.matched(), result.mismatchedWalletIds().size(), result.failedWalletIds().size());
        return result;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        wallets(registry, "matched", matchedWallets);
        wallets(registry, "mismatched", mismatchedWallets);
        wallets(registry, "failed", failedWallets);
        Gauge.builder(LAST_SUCCESS_METRIC, lastSuccessEpochSeconds, AtomicLong::get)
                .description("잔액 대사가 끝까지 돈 마지막 시각(epoch 초). pod가 뜬 뒤 아직 돌지 않았으면 0")
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
     * @param failedWalletIds     대사하지 못한 지갑 ID(오름차순)
     */
    public record Result(Instant since, int matched, List<Long> mismatchedWalletIds, List<Long> failedWalletIds) {

        /** 대상 지갑 수 */
        public int total() {
            return matched + mismatchedWalletIds.size() + failedWalletIds.size();
        }
    }
}
