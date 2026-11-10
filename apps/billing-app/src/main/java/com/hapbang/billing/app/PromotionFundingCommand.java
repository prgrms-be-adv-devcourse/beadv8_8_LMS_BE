package com.hapbang.billing.app;

import java.util.Objects;

import com.hapbang.billing.domain.PromotionFundingSourceType;

// 관리자 프로모션 지갑 충전 요청. 충전 작업 ID 형식은 요청 DTO가 검사하고, 금액(1 HP 이상)은 UseCase가 INVALID_POINT_AMOUNT로 검사한다.
public record PromotionFundingCommand(
        String fundingWorkId,
        long amountHp,
        PromotionFundingSourceType sourceType,
        String sourceRef,
        String memo
) {

    public PromotionFundingCommand {
        Objects.requireNonNull(fundingWorkId, "충전 작업 ID는 필수입니다.");
        Objects.requireNonNull(sourceType, "충전 출처는 필수입니다.");
        Objects.requireNonNull(sourceRef, "충전 근거 번호는 필수입니다.");
    }
}
