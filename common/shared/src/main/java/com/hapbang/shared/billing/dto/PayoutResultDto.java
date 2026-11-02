package com.hapbang.shared.billing.dto;

import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;

// 정상 지급 결과 (내부 API payouts). paidHp는 이 지급 요청이 처리될 때 받는 회원의 수익 HP로 옮긴 금액이고(전체 지급이면 그때 남아 있던 보류 전부),
// remainingHeldHp는 응답 시점에 이 참조 대상 보류에 남은 금액이다. replayed가 true면 같은 지급 요청 ID로 다시 보내 처음 결과를 돌려받은 것이다.
// Billing 내부 정보(지갑·보류·상세·지급 건 ID, 출처 HP 종류별 금액, 충전 건별 소진, 보류 상태, 지급 유형)는 담지 않는다.
public record PayoutResultDto(
        BillingReferenceType referenceType,
        Long referenceId,
        String payoutRequestId,
        Long receiverUserId,
        SettlementSourceType sourceType,
        Long sourceId,
        long paidHp,
        long remainingHeldHp,
        boolean replayed
) {
}
