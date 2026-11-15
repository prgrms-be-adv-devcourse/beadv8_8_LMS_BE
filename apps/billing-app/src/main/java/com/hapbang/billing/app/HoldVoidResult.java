package com.hapbang.billing.app;

import java.util.List;

import com.hapbang.billing.domain.HoldVoid;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.HoldVoidOutcome;

// 보류 무효화의 최초 결과를 재생한다. 프로모션 복구 목록은 무효화가 직접 반환한 경우에만 담는다.
public record HoldVoidResult(
        BillingReferenceType referenceType,
        Long referenceId,
        HoldVoidOutcome outcome,
        long releasedHp,
        long promotionRestoredHp,
        List<ReleaseResult.PromotionRestoration> promotionRestorations,
        boolean replayed
) {
    public HoldVoidResult {
        promotionRestorations = List.copyOf(promotionRestorations);
    }

    public HoldVoidResult(BillingReferenceType referenceType, Long referenceId, HoldVoidOutcome outcome,
            long releasedHp, boolean replayed) {
        this(referenceType, referenceId, outcome, releasedHp, 0L, List.of(), replayed);
    }

    static HoldVoidResult of(HoldVoid holdVoid, boolean replayed) {
        return of(holdVoid, List.of(), replayed);
    }

    static HoldVoidResult of(HoldVoid holdVoid, List<ReleaseResult.PromotionRestoration> restorations,
            boolean replayed) {
        return new HoldVoidResult(holdVoid.getReferenceType(), holdVoid.getReferenceId(), holdVoid.getOutcome(),
                holdVoid.getReleasedHp(), holdVoid.getPromotionRestoredHp(), restorations, replayed);
    }
}
