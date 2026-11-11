package com.hapbang.shared.billing.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

/**
 * 충전 HP 환불 요청이 운영자 확인 필요 상태가 됐다(환불 요청 MANUAL_REVIEW_REQUIRED). 자동 처리는 멈췄고 확인할 충전 건 금액은 보류로 남는다.
 * 환불 요청 하나에 한 번 발행된다 (#873, BD-162). 확인 사유는 싣지 않는다.
 * Kafka {@value BillingEventTopic#NAME} 토픽에 key {@code point-refund-request:<환불 요청 ID>}로 나간다. 받는 쪽 멱등 키는 환불 요청 ID다.
 *
 * @param refundRequestId 환불 요청 ID
 * @param userId 환불을 요청한 회원 ID
 * @param requestedAmountHp 요청한 HP
 * @param manualReviewRequiredAt 운영자 확인이 필요해진 시각(그 충전 건 환불의 운영자 확인 시각)
 */
@Externalized(BillingEventTopic.NAME + "::#{'point-refund-request:' + #this.refundRequestId()}")
public record RefundManualReviewRequiredEvent(Long refundRequestId, Long userId, long requestedAmountHp,
        Instant manualReviewRequiredAt) {
}
