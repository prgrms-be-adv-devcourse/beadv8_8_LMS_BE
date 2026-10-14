package com.hapbang.shared.billing.dto;

import com.hapbang.shared.billing.domain.BillingReferenceType;

// 반환 결과 (BillingPort.release). releasedHp는 이 반환 요청이 처리될 때 되돌린 금액이고(전체 반환이면 그때 남아 있던 보류 전부),
// remainingHeldHp는 응답 시점에 이 참조 대상 보류에 남은 금액이다. replayed가 true면 같은 반환 요청 ID로 다시 보내 처음 결과를 돌려받은 것이다.
// Billing 내부 정보(지갑·보류·상세 ID, HP 종류별 금액, 충전 건별 원금 복원, 보류 상태)는 담지 않는다.
public record ReleaseResultDto(
        BillingReferenceType referenceType,
        Long referenceId,
        String releaseRequestId,
        long releasedHp,
        long remainingHeldHp,
        boolean replayed
) {
}
