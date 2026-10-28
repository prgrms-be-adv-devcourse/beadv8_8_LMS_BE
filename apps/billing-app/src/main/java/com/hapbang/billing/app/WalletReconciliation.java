package com.hapbang.billing.app;

import java.util.List;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.PointType;

// 지갑 캐시 잔액과 원장 합계를 네 가지 HP 종류·잔액 버킷별로 비교한 대사 결과다.
public record WalletReconciliation(Long walletId, List<BucketReconciliation> buckets, boolean matched) {

    // 버킷 결과를 변경할 수 없는 복사본으로 보관한다.
    public WalletReconciliation {
        buckets = List.copyOf(buckets);
    }

    // 버킷별 비교 결과로 대사 결과를 만들고, 모든 버킷의 차이가 0일 때만 일치로 본다.
    public static WalletReconciliation of(Long walletId, List<BucketReconciliation> buckets) {
        return new WalletReconciliation(walletId, buckets, buckets.stream().allMatch(BucketReconciliation::matched));
    }

    // HP 종류·잔액 버킷의 비교 결과를 찾는다.
    public BucketReconciliation bucket(PointType pointType, BalanceBucket balanceBucket) {
        return buckets.stream()
                .filter(bucket -> bucket.pointType() == pointType && bucket.balanceBucket() == balanceBucket)
                .findFirst()
                .orElseThrow();
    }

    // 한 HP 종류·잔액 버킷의 지갑 캐시 금액, 원장 합계, 차이(캐시 - 원장)다.
    public record BucketReconciliation(
            PointType pointType,
            BalanceBucket balanceBucket,
            long walletHp,
            long ledgerHp,
            long differenceHp
    ) {

        // 캐시 금액과 원장 합계로 차이를 계산해 비교 결과를 만든다.
        public static BucketReconciliation of(PointType pointType, BalanceBucket balanceBucket, long walletHp,
                long ledgerHp) {
            return new BucketReconciliation(pointType, balanceBucket, walletHp, ledgerHp,
                    Math.subtractExact(walletHp, ledgerHp));
        }

        // 캐시 금액과 원장 합계가 같은지 반환한다.
        public boolean matched() {
            return differenceHp == 0;
        }
    }
}
