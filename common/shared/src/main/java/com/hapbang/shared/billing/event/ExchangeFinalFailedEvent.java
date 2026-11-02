package com.hapbang.shared.billing.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

/**
 * 관리자가 재송금하지 않고 환전을 최종 실패로 끝냈다(환전 FINAL_FAILED). 보류한 수익 HP는 전부 반환됐다. 환전 하나에 한 번 발행된다 (#497).
 * 최종 실패 사유(관리자 입력)는 싣지 않는다.
 * Kafka {@value BillingEventTopic#NAME} 토픽에 key {@code exchange:<환전 ID>}로 나간다. 받는 쪽 멱등 키는 환전 ID다.
 *
 * @param exchangeId 환전 ID
 * @param userId 환전을 신청한 회원 ID
 * @param requestedAmountHp 신청한(반환된) 수익 HP
 * @param finalFailedAt 최종 실패 시각
 */
@Externalized(BillingEventTopic.NAME + "::#{'exchange:' + #this.exchangeId()}")
public record ExchangeFinalFailedEvent(Long exchangeId, Long userId, long requestedAmountHp, Instant finalFailedAt) {
}
