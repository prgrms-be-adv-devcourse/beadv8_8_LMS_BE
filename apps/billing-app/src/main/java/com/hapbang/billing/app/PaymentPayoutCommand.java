package com.hapbang.billing.app;

import java.util.Objects;

import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;

// 강의·출연 HP 결제 보류의 정상 지급 요청 (#494). 금액이 null이면 처리 시점의 남은 보류 전부를 받는 회원의 수익 HP로 지급한다.
// 지급 원천은 출연 계약·주문 항목 단위이고, 지급 요청 ID는 보류 하나 안에서 고유하다.
// 금액·지갑·보류 소유·지급 원천·자기 지급·중복 정산·멱등성은 Billing이 BillingException으로 검증한다.
public record PaymentPayoutCommand(Long payerUserId, BillingReferenceType referenceType, Long referenceId,
        String payoutRequestId, Long receiverUserId, SettlementSourceType sourceType, Long sourceId, Long amountHp) {

    public PaymentPayoutCommand {
        Objects.requireNonNull(payerUserId, "보류한 회원 ID는 필수입니다.");
        Objects.requireNonNull(receiverUserId, "받는 회원 ID는 필수입니다.");
        Objects.requireNonNull(sourceType, "지급 원천 유형은 필수입니다.");
        Objects.requireNonNull(sourceId, "지급 원천 ID는 필수입니다.");
        PaymentHoldRequests.requireHoldable(referenceType, referenceId);
        PaymentHoldRequests.requireRequestId(payoutRequestId, "지급 요청 ID");
    }

    // 남은 보류 중 요청한 금액만 지급한다.
    public static PaymentPayoutCommand partial(Long payerUserId, BillingReferenceType referenceType, Long referenceId,
            String payoutRequestId, Long receiverUserId, SettlementSourceType sourceType, Long sourceId,
            long amountHp) {
        return new PaymentPayoutCommand(payerUserId, referenceType, referenceId, payoutRequestId, receiverUserId,
                sourceType, sourceId, amountHp);
    }

    // 처리 시점의 남은 보류 전부를 지급한다.
    public static PaymentPayoutCommand full(Long payerUserId, BillingReferenceType referenceType, Long referenceId,
            String payoutRequestId, Long receiverUserId, SettlementSourceType sourceType, Long sourceId) {
        return new PaymentPayoutCommand(payerUserId, referenceType, referenceId, payoutRequestId, receiverUserId,
                sourceType, sourceId, null);
    }

    public boolean isFull() {
        return amountHp == null;
    }
}
