package com.hapbang.billing.domain;

import java.util.EnumSet;
import java.util.Set;

// HP의 종류를 회원의 충전 HP·수익 HP, 플랫폼의 수수료 수입, 프로모션 지갑의 프로모션 HP로 구분한다.
public enum PointType {
    /** 충전 HP. 현금으로 충전한 예치금이다. 환불할 수 있고 환전할 수 없다. HP 결제 때 수익 HP를 다 쓴 뒤에 쓴다 */
    CHARGE(EnumSet.of(BalanceBucket.AVAILABLE, BalanceBucket.HELD)),
    /** 수익 HP. 강의 판매·출연료로 지급받은 HP다. 환전할 수 있고 환불할 수 없다. HP 결제 때 먼저 쓴다 */
    REVENUE(EnumSet.of(BalanceBucket.AVAILABLE, BalanceBucket.HELD)),
    /** 수수료 수입. 환전 수수료처럼 플랫폼이 가져가는 HP다. 플랫폼 지갑의 가용 칸에만 있고 결제·환불·환전에 쓰지 않는다 (#336) */
    FEE(EnumSet.of(BalanceBucket.AVAILABLE)),
    /**
     * 프로모션 HP. 관리자가 플랫폼 쿠폰 재원으로 충전한 HP이며 프로모션 지갑에만 있다. 가용(미배정)·예약(쿠폰 예산으로 묶임)·
     * 보류(주문 항목 보류) 칸이 있고 환불·환전하지 않는다. 판매자에게 지급되면 판매자의 수익 HP가 된다
     */
    PROMOTION(EnumSet.of(BalanceBucket.AVAILABLE, BalanceBucket.RESERVED, BalanceBucket.HELD));

    private final Set<BalanceBucket> balanceBuckets;

    PointType(Set<BalanceBucket> balanceBuckets) {
        this.balanceBuckets = balanceBuckets;
    }

    // 이 HP 종류에 있는 잔액 칸인지 확인한다. 없는 칸(예: 수수료 수입의 보류, 충전 HP의 예약)에는 원장을 기록하지 않는다.
    public boolean has(BalanceBucket balanceBucket) {
        return balanceBuckets.contains(balanceBucket);
    }
}
