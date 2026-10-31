package com.hapbang.billing.out;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.PointType;

// 한 지갑의 HP 종류·잔액 버킷별 원장 증감 합계를 담는 조회 결과다.
public record LedgerBucketSum(PointType pointType, BalanceBucket balanceBucket, Long amountHp) {
}
