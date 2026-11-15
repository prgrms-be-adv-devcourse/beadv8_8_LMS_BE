package com.hapbang.billing.app;

import java.util.List;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.PointType;

// 지갑 캐시 잔액과 원장 합계를 HP 종류·잔액 버킷별(충전·수익 × 가용·보류, 수수료 수입 가용, 프로모션 HP × 가용·예약·보류)로 비교한 대사 결과다.
// 프로모션 지갑은 버킷 비교에 더해 프로모션 지갑 불변식(가용 칸 식, 예약·보류 칸 = 예산 예약 합, 예약별 원장 합) 검사 결과가 있다.
public record WalletReconciliation(Long walletId, List<BucketReconciliation> buckets, List<InvariantCheck> invariants,
        boolean matched) {

    // 결과를 변경할 수 없는 복사본으로 보관한다.
    public WalletReconciliation {
        buckets = List.copyOf(buckets);
        invariants = List.copyOf(invariants);
    }

    // 버킷별 비교 결과로 대사 결과를 만들고, 모든 버킷의 차이가 0일 때만 일치로 본다.
    public static WalletReconciliation of(Long walletId, List<BucketReconciliation> buckets) {
        return of(walletId, buckets, List.of());
    }

    // 버킷 비교와 불변식 검사가 모두 맞을 때만 일치로 본다.
    public static WalletReconciliation of(Long walletId, List<BucketReconciliation> buckets,
            List<InvariantCheck> invariants) {
        return new WalletReconciliation(walletId, buckets, invariants,
                buckets.stream().allMatch(BucketReconciliation::matched)
                        && invariants.stream().allMatch(InvariantCheck::matched));
    }

    // HP 종류·잔액 버킷의 비교 결과를 찾는다.
    public BucketReconciliation bucket(PointType pointType, BalanceBucket balanceBucket) {
        return buckets.stream()
                .filter(bucket -> bucket.pointType() == pointType && bucket.balanceBucket() == balanceBucket)
                .findFirst()
                .orElseThrow();
    }

    // 이름으로 불변식 검사 결과를 찾는다.
    public InvariantCheck invariant(String name) {
        return invariants.stream().filter(check -> check.name().equals(name)).findFirst().orElseThrow();
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

    // 불변식 하나의 검사 결과. 왼쪽 값과 오른쪽 값이 관계(같음 "=" 또는 이하 "<=")를 지키면 맞다.
    public record InvariantCheck(String name, long leftHp, String relation, long rightHp, boolean matched) {

        public static InvariantCheck equal(String name, long leftHp, long rightHp) {
            return new InvariantCheck(name, leftHp, "=", rightHp, leftHp == rightHp);
        }

        public static InvariantCheck atMost(String name, long leftHp, long rightHp) {
            return new InvariantCheck(name, leftHp, "<=", rightHp, leftHp <= rightHp);
        }
    }
}
