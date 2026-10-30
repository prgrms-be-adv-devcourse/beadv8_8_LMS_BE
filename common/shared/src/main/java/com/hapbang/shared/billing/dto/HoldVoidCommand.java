package com.hapbang.shared.billing.dto;

import java.util.Objects;

import com.hapbang.shared.billing.domain.BillingReferenceType;

// 참조 대상의 HP 보류를 더는 쓰지 않는다고 알리는 보류 무효화 요청이다 (#479).
// 남은 보류는 결제 회원에게 반환하고, 아직 보류가 없으면 앞으로 그 참조 대상에 보류가 만들어지지 않게 한다.
// 멱등 키는 참조 대상(referenceType + referenceId)이다. 참조 대상당 한 번이고, 같은 요청을 다시 보내면 처음 결과를 돌려받는다.
// 이 record는 필수 값·참조 유형 같은 요청 구조만 검사한다.
public record HoldVoidCommand(
        Long userId,
        BillingReferenceType referenceType,
        Long referenceId
) {

    // 필수 값과 보류 가능한 참조 유형을 확인한다.
    public HoldVoidCommand {
        Objects.requireNonNull(userId, "회원 ID는 필수입니다.");
        BillingRequests.requireHoldable(referenceType, referenceId);
    }
}
