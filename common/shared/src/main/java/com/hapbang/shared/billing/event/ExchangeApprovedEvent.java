package com.hapbang.shared.billing.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

/**
 * 관리자가 환전 신청을 승인했다(환전 APPROVED). 환전 하나에 한 번 발행된다 (#873, BD-162).
 * 수수료·송금액은 송금이 끝나면 {@link ExchangeCompletedEvent}가 알린다.
 * Kafka {@value BillingEventTopic#NAME} 토픽에 key {@code exchange:<환전 ID>}로 나간다. 받는 쪽 멱등 키는 환전 ID다.
 *
 * @param exchangeId 환전 ID
 * @param userId 환전을 신청한 회원 ID
 * @param requestedAmountHp 신청한 수익 HP
 * @param payoutScheduledAt 승인 때 정한 정산(송금) 예정 시각
 * @param approvedAt 승인 시각
 */
@Externalized(BillingEventTopic.NAME + "::#{'exchange:' + #this.exchangeId()}")
public record ExchangeApprovedEvent(Long exchangeId, Long userId, long requestedAmountHp, Instant payoutScheduledAt,
        Instant approvedAt) {
}
