package com.hapbang.shared.billing.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

/**
 * 충전 HP 환불 요청이 환불한 금액 없이 실패로 끝났다(환불 요청 FAILED). 보류한 충전 HP는 모두 반환됐다. 환불 요청 하나에 한 번 발행된다
 * (#873, BD-162). 일부 환불(PARTIALLY_COMPLETED)은 이 이벤트가 아니라 {@link RefundCompletedEvent}로 알린다. 실패 사유는 싣지 않는다.
 * Kafka {@value BillingEventTopic#NAME} 토픽에 key {@code point-refund-request:<환불 요청 ID>}로 나간다. 받는 쪽 멱등 키는 환불 요청 ID다.
 *
 * @param refundRequestId 환불 요청 ID
 * @param userId 환불을 요청한 회원 ID
 * @param requestedAmountHp 요청한 HP
 * @param failedAt 환불 처리가 끝난 시각
 */
@Externalized(BillingEventTopic.NAME + "::#{'point-refund-request:' + #this.refundRequestId()}")
public record RefundFailedEvent(Long refundRequestId, Long userId, long requestedAmountHp, Instant failedAt) {
}
