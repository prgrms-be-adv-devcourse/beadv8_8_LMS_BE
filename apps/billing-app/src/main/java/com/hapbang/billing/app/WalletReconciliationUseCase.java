package com.hapbang.billing.app;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.LedgerBucketSum;
import com.hapbang.billing.out.PointLedgerRepository;
import com.hapbang.billing.out.WalletRepository;

import lombok.RequiredArgsConstructor;

// 지갑 캐시 잔액과 원장 합계를 비교만 하며, 불일치가 있어도 원장이나 지갑을 고치지 않는다.
@Service
@RequiredArgsConstructor
public class WalletReconciliationUseCase {

    private final WalletRepository walletRepository;
    private final PointLedgerRepository pointLedgerRepository;

    // 지갑과 원장 합계를 한 스냅숏(REPEATABLE READ)에서 읽어, 동시 거래로 생기는 일시적 불일치를 잘못 탐지하지 않게 비교한다.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WalletReconciliation reconcile(Long walletId) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.WALLET_NOT_FOUND));
        Map<PointType, Map<BalanceBucket, Long>> ledgerSums = ledgerSums(walletId);

        List<WalletReconciliation.BucketReconciliation> buckets = new ArrayList<>();
        for (PointType pointType : PointType.values()) {
            for (BalanceBucket balanceBucket : BalanceBucket.values()) {
                // HP 종류에 있는 칸만 비교한다(수수료 수입은 가용 칸만, 예약 칸은 프로모션 HP만).
                // 지갑 유형과 관계없이 모든 칸을 비교해, 그 지갑에 없어야 할 HP 종류의 칸도 캐시·원장 모두 0인지 확인한다
                if (!pointType.has(balanceBucket)) {
                    continue;
                }
                long ledgerHp = ledgerSums.get(pointType).getOrDefault(balanceBucket, 0L);
                buckets.add(WalletReconciliation.BucketReconciliation.of(
                        pointType, balanceBucket, wallet.balanceOf(pointType, balanceBucket), ledgerHp));
            }
        }
        return WalletReconciliation.of(walletId, buckets);
    }

    // 원장 합계를 HP 종류·잔액 버킷별로 정리하며, 원장이 없는 버킷은 0으로 본다.
    private Map<PointType, Map<BalanceBucket, Long>> ledgerSums(Long walletId) {
        Map<PointType, Map<BalanceBucket, Long>> sums = new EnumMap<>(PointType.class);
        for (PointType pointType : PointType.values()) {
            sums.put(pointType, new EnumMap<>(BalanceBucket.class));
        }
        for (LedgerBucketSum sum : pointLedgerRepository.sumAmountByWalletId(walletId)) {
            sums.get(sum.pointType()).put(sum.balanceBucket(), sum.amountHp());
        }
        return sums;
    }
}
