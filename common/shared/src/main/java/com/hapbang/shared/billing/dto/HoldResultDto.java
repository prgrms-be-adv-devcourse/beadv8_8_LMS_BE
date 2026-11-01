package com.hapbang.shared.billing.dto;

import com.hapbang.shared.billing.domain.BillingReferenceType;

// 보류 결과 (내부 API holds). 보류는 실제로 만들어진 뒤에만 돌려주며, 잔액 부족은 결과가 아니라 예외(INSUFFICIENT_BALANCE)로 알린다.
// heldHp는 이 참조 대상의 최초 보류액이다. replayed가 true면 같은 요청을 다시 보내 기존 보류를 그대로 돌려받은 것이다.
// Billing 내부 정보(지갑·보류 ID, HP 종류별 금액, 충전 건별 배분)는 담지 않는다.
public record HoldResultDto(
        BillingReferenceType referenceType,
        Long referenceId,
        long heldHp,
        boolean replayed
) {
}
