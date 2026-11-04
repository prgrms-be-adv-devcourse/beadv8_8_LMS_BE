package com.hapbang.shared.billing.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

/**
 * 충전 HP 환불 요청이 환불로 끝났다(전부 환불 COMPLETED 또는 일부 환불 PARTIALLY_COMPLETED). 환불 요청 하나에 한 번 발행된다 (#497).
 * 일부 환불이면 환불하지 못한 금액은 충전 HP로 반환됐다. 환불한 금액이 없으면(FAILED)·회원이 취소하면(CANCELED) 발행하지 않는다.
 * Kafka {@value BillingEventTopic#NAME} 토픽에 key {@code point-refund-request:<환불 요청 ID>}로 나간다. 받는 쪽 멱등 키는 환불 요청 ID다.
 *
 * @param refundRequestId 환불 요청 ID
 * @param userId 환불을 요청한 회원 ID
 * @param requestedAmountHp 요청한 HP
 * @param refundedAmountHp 실제로 환불한 HP(전부 환불이면 요청 금액과 같다)
 * @param completedAt 환불 처리가 끝난 시각
 */
@Externalized(BillingEventTopic.NAME + "::#{'point-refund-request:' + #this.refundRequestId()}")
public record RefundCompletedEvent(Long refundRequestId, Long userId, long requestedAmountHp, long refundedAmountHp,
        Instant completedAt) {

    /** 요청한 금액을 모두 환불했는지(아니면 일부 환불) */
    public boolean fullyRefunded() {
        return requestedAmountHp == refundedAmountHp;
    }
}
