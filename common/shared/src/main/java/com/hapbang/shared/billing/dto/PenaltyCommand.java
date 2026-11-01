package com.hapbang.shared.billing.dto;

import java.util.Objects;

import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;

// 호스트가 성립한 출연을 일방 취소했을 때, 보류에서 호스트 반환액을 호스트에게 되돌리고 게스트 지급액을 게스트 수익 HP에 위약금으로 지급하는 요청이다
// (내부 API penalty-settlements). 두 처리는 Billing이 한 트랜잭션에서 함께 한다.
// 출연이 정하는 것(2026-10-14 확정): 취소 가능 여부, 취소 대상 게스트·출연 계약, 계약 출연료, 위약금 비율·금액 계산(반올림 포함), 호스트 반환액.
// Billing은 비율을 계산하거나 검증하지 않는다. 두 금액의 합(releaseAmountHp + payoutAmountHp)이 이 취소 건의 정산 기준액이다.
// 취소 ID는 보류 하나 안에서 고유하며, 같은 취소 사건을 다시 보낼 때는 같은 값·같은 금액을 쓴다.
// 이 record는 요청 구조만 검사한다. 금액(반환액 0 이상, 지급액 1 이상, 합계 ≤ 남은 보류)·지갑·자기 지급·지급 원천·중복 정산·멱등성은
// Billing이 BillingException으로 검증한다.
public record PenaltyCommand(
        Long payerUserId,
        BillingReferenceType referenceType,
        Long referenceId,
        String cancellationId,
        Long receiverUserId,
        SettlementSourceType sourceType,
        Long sourceId,
        long releaseAmountHp,
        long payoutAmountHp
) {

    // 필수 값·취소 ID 형식을 확인한다.
    public PenaltyCommand {
        Objects.requireNonNull(payerUserId, "보류한 회원 ID는 필수입니다.");
        Objects.requireNonNull(receiverUserId, "받는 회원 ID는 필수입니다.");
        Objects.requireNonNull(sourceType, "지급 원천 유형은 필수입니다.");
        Objects.requireNonNull(sourceId, "지급 원천 ID는 필수입니다.");
        BillingRequests.requireHoldable(referenceType, referenceId);
        BillingRequests.requireRequestId(cancellationId, "취소 ID");
    }
}
