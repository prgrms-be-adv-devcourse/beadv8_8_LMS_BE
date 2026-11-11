package com.hapbang.shared.billing.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hapbang.shared.billing.domain.BillingReferenceType;

// releasedHp는 구매자에게 되돌린 금액, promotionRestoredHp는 원래 쿠폰 예산으로 복구한 플랫폼 몫이다.
// 플랫폼 몫이 없으면 추가 필드는 JSON에서 생략해 기존 응답을 유지한다.
public record ReleaseResultDto(
        BillingReferenceType referenceType,
        Long referenceId,
        String releaseRequestId,
        long releasedHp,
        long remainingHeldHp,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) Long promotionRestoredHp,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<PromotionRestorationDto> promotionRestorations,
        boolean replayed
) {
    public ReleaseResultDto {
        promotionRestoredHp = promotionRestoredHp == null ? 0L : promotionRestoredHp;
        promotionRestorations = promotionRestorations == null ? List.of() : List.copyOf(promotionRestorations);
    }

    public ReleaseResultDto(BillingReferenceType referenceType, Long referenceId, String releaseRequestId,
            long releasedHp, long remainingHeldHp, boolean replayed) {
        this(referenceType, referenceId, releaseRequestId, releasedHp, remainingHeldHp, 0L, List.of(), replayed);
    }
}
