package com.hapbang.billing.app;

import java.util.Objects;

// 쿠폰 예산 예약 요청(Billing 내부). 작업 ID 형식·쿠폰 ID·정책 버전은 shared 요청이 검사하고, 금액은 UseCase가 INVALID_POINT_AMOUNT로 검사한다.
public record BudgetReserveCommand(String workId, long couponId, int policyVersion, long amountHp) {

    public BudgetReserveCommand {
        Objects.requireNonNull(workId, "작업 ID는 필수입니다.");
    }
}
