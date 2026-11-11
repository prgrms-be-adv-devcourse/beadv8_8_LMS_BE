package com.hapbang.billing.app;

import java.util.Objects;

// 관리자 프로모션 지갑 회수 요청. 회수 작업 ID 형식은 요청 DTO가 검사하고, 금액(1 HP 이상)은 UseCase가 INVALID_POINT_AMOUNT로 검사한다.
public record PromotionWithdrawalCommand(
        String withdrawalWorkId,
        long amountHp,
        String referenceNo,
        String memo
) {

    public PromotionWithdrawalCommand {
        Objects.requireNonNull(withdrawalWorkId, "회수 작업 ID는 필수입니다.");
        Objects.requireNonNull(referenceNo, "회수 근거 번호는 필수입니다.");
    }
}
