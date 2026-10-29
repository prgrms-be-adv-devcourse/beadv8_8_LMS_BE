package com.hapbang.shared.billing.dto;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;

// 참조 대상 하나의 보류에서 HP 일부(부분 지급) 또는 남은 보류 전부(전체 지급)를 받는 회원의 수익 HP로 옮기는 정상 지급 요청이다 (BillingPort.payout).
// 강의·출연이 지급 가능 여부와 금액을 정한다. 지급 원천은 출연 계약·주문 항목 단위다(1:1 = 출연 계약 ID, 1:N = 게스트별 출연 계약 ID, 강의 = 주문 항목 ID).
// 지급 요청 ID는 보류 하나 안에서 고유하며, 같은 업무 요청을 다시 보낼 때는 같은 값을 쓴다(호출마다 새 UUID를 만들지 않는다).
// 이 record는 요청 구조만 검사한다. 금액·지갑·보류 소유·지급 원천·자기 지급·중복 정산·멱등성은 Billing이 BillingException으로 검증한다.
public record PayoutCommand(
        Long payerUserId,
        BillingReferenceType referenceType,
        Long referenceId,
        String payoutRequestId,
        Long receiverUserId,
        SettlementSourceType sourceType,
        Long sourceId,
        Long amountHp
) {

    // 필수 값·지급 요청 ID 형식을 확인한다. 금액이 null이면 전체 지급이다.
    public PayoutCommand {
        Objects.requireNonNull(payerUserId, "보류한 회원 ID는 필수입니다.");
        Objects.requireNonNull(receiverUserId, "받는 회원 ID는 필수입니다.");
        Objects.requireNonNull(sourceType, "지급 원천 유형은 필수입니다.");
        Objects.requireNonNull(sourceId, "지급 원천 ID는 필수입니다.");
        BillingRequests.requireHoldable(referenceType, referenceId);
        BillingRequests.requireRequestId(payoutRequestId, "지급 요청 ID");
    }

    // 남은 보류액 중 요청한 금액만 지급하는 부분 지급 요청을 만든다.
    public static PayoutCommand partial(Long payerUserId, BillingReferenceType referenceType, Long referenceId,
            String payoutRequestId, Long receiverUserId, SettlementSourceType sourceType, Long sourceId,
            long amountHp) {
        return new PayoutCommand(payerUserId, referenceType, referenceId, payoutRequestId, receiverUserId,
                sourceType, sourceId, amountHp);
    }

    // 처리 시점에 남아 있는 보류액 전부를 지급하는 전체 지급 요청을 만든다.
    public static PayoutCommand full(Long payerUserId, BillingReferenceType referenceType, Long referenceId,
            String payoutRequestId, Long receiverUserId, SettlementSourceType sourceType, Long sourceId) {
        return new PayoutCommand(payerUserId, referenceType, referenceId, payoutRequestId, receiverUserId,
                sourceType, sourceId, null);
    }

    // 금액 없이 남은 보류 전부를 지급하는 요청인지 확인한다.
    // 요청 본문(JSON)에 넣지 않는다. 금액이 null인 것으로 충분하다 (#272)
    @JsonIgnore
    public boolean isFull() {
        return amountHp == null;
    }
}
