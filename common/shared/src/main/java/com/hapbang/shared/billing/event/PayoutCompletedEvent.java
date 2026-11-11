package com.hapbang.shared.billing.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hapbang.shared.billing.domain.PayoutType;
import com.hapbang.shared.billing.domain.SettlementSourceType;

/**
 * 보류 HP를 받는 회원의 수익 HP로 지급했다(지급 건 하나 생성). 정상 지급과 위약금 지급 모두 발행한다 (#497).
 * Kafka {@value BillingEventTopic#NAME} 토픽에 key {@code settlement:<지급 건 ID>}로 나간다. 받는 쪽 멱등 키는 지급 건 ID다.
 *
 * @param settlementId 지급 건 ID
 * @param receiverUserId 받은 회원 ID
 * @param payoutType 정상 지급(NORMAL) 또는 위약금 지급(PENALTY)
 * @param sourceType 지급 원천 유형(강의 주문 항목, 출연 계약)
 * @param sourceId 지급 원천 ID
 * @param paidAmountHp 지급한 HP
 * @param paidAt 지급 시각
 * @param sourceTitle 출처(출연·강의) 제목. 지급 요청에 제목이 없으면 null이다 (#873, BD-163·알림 ND-040)
 */
@Externalized(BillingEventTopic.NAME + "::#{'settlement:' + #this.settlementId()}")
public record PayoutCompletedEvent(Long settlementId, Long receiverUserId, PayoutType payoutType,
        SettlementSourceType sourceType, Long sourceId, long paidAmountHp, Instant paidAt,
        // 제목이 없으면 본문에 쓰지 않는다(#873 이전 본문과 같다)
        @JsonInclude(JsonInclude.Include.NON_NULL) String sourceTitle) {

    /** 출처 제목 없이 만든다 (#873 이전 형식과 같다) */
    public PayoutCompletedEvent(Long settlementId, Long receiverUserId, PayoutType payoutType,
            SettlementSourceType sourceType, Long sourceId, long paidAmountHp, Instant paidAt) {
        this(settlementId, receiverUserId, payoutType, sourceType, sourceId, paidAmountHp, paidAt, null);
    }
}
