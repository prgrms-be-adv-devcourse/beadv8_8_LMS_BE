package com.hapbang.shared.billing.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.HoldVoidOutcome;

// 무효화가 직접 반환한 구매자 몫과 플랫폼 예산 복구 결과를 최초 값 그대로 재생한다.
public record HoldVoidResultDto(
        BillingReferenceType referenceType,
        Long referenceId,
        HoldVoidOutcome outcome,
        long releasedHp,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) Long promotionRestoredHp,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<PromotionRestorationDto> promotionRestorations,
        boolean replayed
) {
    public HoldVoidResultDto {
        promotionRestoredHp = promotionRestoredHp == null ? 0L : promotionRestoredHp;
        promotionRestorations = promotionRestorations == null ? List.of() : List.copyOf(promotionRestorations);
    }

    public HoldVoidResultDto(BillingReferenceType referenceType, Long referenceId, HoldVoidOutcome outcome,
            long releasedHp, boolean replayed) {
        this(referenceType, referenceId, outcome, releasedHp, 0L, List.of(), replayed);
    }
}
