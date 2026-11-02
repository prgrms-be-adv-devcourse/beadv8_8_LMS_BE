package com.hapbang.shared.billing.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

/**
 * HP 충전이 완료됐다(충전 건 COMPLETED). 충전 건 하나에 한 번 발행된다 (#497).
 * Kafka {@value BillingEventTopic#NAME} 토픽에 key {@code point-charge:<충전 ID>}로 나간다. 받는 쪽 멱등 키는 충전 ID다.
 *
 * @param chargeId 충전 건 ID
 * @param userId 충전한 회원 ID
 * @param amountHp 충전한 HP
 * @param paymentAmountKrw PG 결제 금액(KRW)
 * @param completedAt 충전 완료 시각
 */
@Externalized(BillingEventTopic.NAME + "::#{'point-charge:' + #this.chargeId()}")
public record ChargeCompletedEvent(Long chargeId, Long userId, long amountHp, long paymentAmountKrw,
        Instant completedAt) {
}
