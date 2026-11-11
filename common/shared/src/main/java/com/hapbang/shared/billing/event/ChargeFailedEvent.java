package com.hapbang.shared.billing.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

/**
 * HP 충전이 실패로 확정됐다(충전 건 FAILED). 충전 건 하나에 한 번 발행된다 (#873, BD-162).
 * 실패 사유(PG 응답)는 싣지 않는다.
 * Kafka {@value BillingEventTopic#NAME} 토픽에 key {@code point-charge:<충전 ID>}로 나간다. 받는 쪽 멱등 키는 충전 ID다.
 *
 * @param chargeId 충전 건 ID
 * @param userId 충전한 회원 ID
 * @param amountHp 충전하려던 HP
 * @param paymentAmountKrw PG 결제 금액(KRW)
 * @param failedAt 실패 확정 시각
 */
@Externalized(BillingEventTopic.NAME + "::#{'point-charge:' + #this.chargeId()}")
public record ChargeFailedEvent(Long chargeId, Long userId, long amountHp, long paymentAmountKrw, Instant failedAt) {
}
