package com.hapbang.billing.app;

import java.util.Objects;

import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;

// 호스트 일방 취소 위약금 정산 요청 (#494). 출연이 계산한 호스트 반환액을 반환하고 게스트 지급액을 위약금으로 지급한다(한 트랜잭션).
// 두 금액의 합이 정산 기준액이다. 금액(반환액 0 이상, 지급액 1 이상)은 BillingCommandValidator가 검사한다.
public record PaymentPenaltyCommand(Long payerUserId, BillingReferenceType referenceType, Long referenceId,
        String cancellationId, Long receiverUserId, SettlementSourceType sourceType, Long sourceId,
        long releaseAmountHp, long payoutAmountHp) {

    public PaymentPenaltyCommand {
        Objects.requireNonNull(payerUserId, "보류한 회원 ID는 필수입니다.");
        Objects.requireNonNull(receiverUserId, "받는 회원 ID는 필수입니다.");
        Objects.requireNonNull(sourceType, "지급 원천 유형은 필수입니다.");
        Objects.requireNonNull(sourceId, "지급 원천 ID는 필수입니다.");
        PaymentHoldRequests.requireHoldable(referenceType, referenceId);
        PaymentHoldRequests.requireRequestId(cancellationId, "취소 ID");
    }
}
