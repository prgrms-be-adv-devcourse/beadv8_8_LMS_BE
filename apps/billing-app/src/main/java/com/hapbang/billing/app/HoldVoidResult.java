package com.hapbang.billing.app;

import com.hapbang.billing.domain.HoldVoid;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.HoldVoidOutcome;

// 보류 무효화 결과 (#479). replayed가 true면 같은 참조 대상의 처음 무효화 결과를 다시 돌려준 것이다.
public record HoldVoidResult(
        BillingReferenceType referenceType,
        Long referenceId,
        HoldVoidOutcome outcome,
        long releasedHp,
        boolean replayed
) {

    static HoldVoidResult of(HoldVoid holdVoid, boolean replayed) {
        return new HoldVoidResult(holdVoid.getReferenceType(), holdVoid.getReferenceId(), holdVoid.getOutcome(),
                holdVoid.getReleasedHp(), replayed);
    }
}
