package com.hapbang.shared.billing.dto;

import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.HoldVoidOutcome;

// 보류 무효화 결과 (#479). releasedHp는 이 무효화가 반환한 금액이다(RELEASED일 때만 0보다 크다).
// replayed가 true면 같은 참조 대상으로 다시 보내 처음 결과를 돌려받은 것이다.
public record HoldVoidResultDto(
        BillingReferenceType referenceType,
        Long referenceId,
        HoldVoidOutcome outcome,
        long releasedHp,
        boolean replayed
) {
}
