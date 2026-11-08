package com.hapbang.billing.out;

import com.hapbang.billing.domain.HoldDetailActionType;

// 보류 하나의 처리 유형별 HP 종류 합 (#577). 합이 없으면(처리 건 없음) 행이 없다
public record HoldDetailSum(
        Long pointHoldId,
        HoldDetailActionType actionType,
        Long chargeHp,
        Long revenueHp
) {
}
