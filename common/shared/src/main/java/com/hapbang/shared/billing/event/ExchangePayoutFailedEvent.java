package com.hapbang.shared.billing.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

/**
 * 환전 송금 시도 하나가 확정 실패했다(환전 FAILED, 중간 상태). 보류한 수익 HP는 그대로 두고 관리자가 재송금 또는 최종 실패를 정한다.
 * 재송금하면 같은 환전에서 여러 번 발행될 수 있어 송금 시도마다 발행한다 (#497). 실패 사유 원문은 싣지 않는다.
 * Kafka {@value BillingEventTopic#NAME} 토픽에 key {@code exchange:<환전 ID>}로 나간다(같은 환전의 다른 이벤트와 같은 partition).
 * 받는 쪽 멱등 키는 송금 시도 ID다. 순서가 바뀌어 이 이벤트가 같은 환전의 완료·최종 실패보다 늦게 올 수 있다.
 *
 * @param exchangeId 환전 ID
 * @param payoutAttemptId 송금 시도 ID
 * @param attemptNo 몇 번째 송금 시도인지(1부터)
 * @param userId 환전을 신청한 회원 ID
 * @param payoutAmountKrw 송금하려던 금액(KRW)
 * @param failedAt 실패 확정 시각
 */
@Externalized(BillingEventTopic.NAME + "::#{'exchange:' + #this.exchangeId()}")
public record ExchangePayoutFailedEvent(Long exchangeId, Long payoutAttemptId, int attemptNo, Long userId,
        long payoutAmountKrw, Instant failedAt) {
}
