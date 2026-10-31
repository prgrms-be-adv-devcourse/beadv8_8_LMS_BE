package com.hapbang.shared.billing.dto;

import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;

// 위약금 정산 결과 (내부 API penalty-settlements). releasedHp는 호스트에게 반환한 금액, paidHp는 게스트에게 위약금으로 지급한 금액으로
// 요청한 두 금액 그대로다. remainingHeldHp는 응답 시점에 이 참조 대상 보류에 남은 금액이다.
// replayed가 true면 같은 취소 ID·같은 금액으로 다시 보내 처음 결과를 돌려받은 것이다.
// Billing 내부 정보(지갑·보류·상세·지급 건 ID, HP 종류별 금액, 충전 건별 복원·소진, 보류 상태)는 담지 않는다.
public record PenaltyResultDto(
        BillingReferenceType referenceType,
        Long referenceId,
        String cancellationId,
        Long receiverUserId,
        SettlementSourceType sourceType,
        Long sourceId,
        long releasedHp,
        long paidHp,
        long remainingHeldHp,
        boolean replayed
) {
}
