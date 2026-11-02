package com.hapbang.shared.billing.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

/**
 * 관리자가 환전 신청을 거절했다(환전 REJECTED). 보류한 수익 HP는 전부 반환됐다. 환전 하나에 한 번 발행된다 (#497).
 * 거절 사유(관리자 입력)는 싣지 않는다.
 * Kafka {@value BillingEventTopic#NAME} 토픽에 key {@code exchange:<환전 ID>}로 나간다. 받는 쪽 멱등 키는 환전 ID다.
 *
 * @param exchangeId 환전 ID
 * @param userId 환전을 신청한 회원 ID
 * @param requestedAmountHp 신청한(반환된) 수익 HP
 * @param rejectedAt 거절 시각
 */
@Externalized(BillingEventTopic.NAME + "::#{'exchange:' + #this.exchangeId()}")
public record ExchangeRejectedEvent(Long exchangeId, Long userId, long requestedAmountHp, Instant rejectedAt) {
}
