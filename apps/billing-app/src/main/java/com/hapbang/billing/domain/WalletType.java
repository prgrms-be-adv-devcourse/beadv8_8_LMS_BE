package com.hapbang.billing.domain;

import java.util.EnumSet;
import java.util.Set;

// 지갑이 회원의 것인지 플랫폼의 것인지, 플랫폼 쿠폰 재원을 담는 프로모션 지갑인지 구분한다.
public enum WalletType {
    /** 회원 지갑. 회원 한 명에 하나이며 충전 HP·수익 HP를 담는다. 수수료 수입은 담지 않는다 */
    MEMBER(EnumSet.of(PointType.CHARGE, PointType.REVENUE)),
    /** 플랫폼 지갑. 회원이 없고 하나뿐이며 수수료 수입만 담는다. 회원 기능에는 나오지 않고 관리자만 조회한다 */
    PLATFORM(EnumSet.of(PointType.FEE)),
    /** 프로모션 지갑. 회원이 없고 하나뿐이며 플랫폼 쿠폰 재원(프로모션 HP)만 담는다. 회원 기능에는 나오지 않는다 */
    PROMOTION(EnumSet.of(PointType.PROMOTION));

    private final Set<PointType> pointTypes;

    WalletType(Set<PointType> pointTypes) {
        this.pointTypes = pointTypes;
    }

    // 이 유형의 지갑이 담을 수 있는 HP 종류인지 확인한다. 다른 HP 종류의 원장은 이 지갑에 반영하지 않는다.
    public boolean holds(PointType pointType) {
        return pointTypes.contains(pointType);
    }
}
