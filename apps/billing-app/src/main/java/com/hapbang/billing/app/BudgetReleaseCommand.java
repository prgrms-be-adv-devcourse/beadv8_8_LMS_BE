package com.hapbang.billing.app;

import java.util.Objects;

// 쿠폰 예산 해제 요청(Billing 내부). 예약은 예약 행 ID로 가리킨다. 형식·길이는 shared 요청이 검사하고, 금액은 UseCase가 검사한다.
public record BudgetReleaseCommand(String workId, long reservationId, long amountHp, String target, String reason) {

    public BudgetReleaseCommand {
        Objects.requireNonNull(workId, "작업 ID는 필수입니다.");
        Objects.requireNonNull(target, "해제 대상은 필수입니다.");
    }
}
