package com.hapbang.shared.billing.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

/**
 * 환전 송금이 완료됐다(환전 COMPLETED). 송금액 0원 환전(은행 송금 없이 완료)도 포함한다. 환전 하나에 한 번 발행된다 (#497).
 * Kafka {@value BillingEventTopic#NAME} 토픽에 key {@code exchange:<환전 ID>}로 나간다. 받는 쪽 멱등 키는 환전 ID다.
 *
 * @param exchangeId 환전 ID
 * @param userId 환전을 신청한 회원 ID
 * @param requestedAmountHp 신청한 수익 HP
 * @param feeAmountHp 수수료(HP)
 * @param payoutAmountKrw 송금액(KRW)
 * @param completedAt 송금 완료 시각
 */
@Externalized(BillingEventTopic.NAME + "::#{'exchange:' + #this.exchangeId()}")
public record ExchangeCompletedEvent(Long exchangeId, Long userId, long requestedAmountHp, long feeAmountHp,
        long payoutAmountKrw, Instant completedAt) {
}
