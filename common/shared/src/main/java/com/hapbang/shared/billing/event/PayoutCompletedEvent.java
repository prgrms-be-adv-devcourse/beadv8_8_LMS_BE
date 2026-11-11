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
 * @param sourceTitle 출처(출연·강의) 제목. 지급 요청에 제목이 없으면 null이고, 최대 {@value #SOURCE_TITLE_MAX}자(code point)다
 *                    (#873, BD-163·알림 ND-040)
 */
@Externalized(BillingEventTopic.NAME + "::#{'settlement:' + #this.settlementId()}")
public record PayoutCompletedEvent(Long settlementId, Long receiverUserId, PayoutType payoutType,
        SettlementSourceType sourceType, Long sourceId, long paidAmountHp, Instant paidAt,
        // 제목이 없으면 본문에 쓰지 않는다(#873 이전 본문과 같다)
        @JsonInclude(JsonInclude.Include.NON_NULL) String sourceTitle) {

    /**
     * 이벤트에 담는 출처 제목의 최대 문자(code point) 수. 출연·강의의 칼럼 길이를 따라가지 않는 지갑 이벤트의 고정 상한이다(BD-163).
     * 정할 때 출처 제목 중 가장 긴 것(강의 255자)을 자르지 않도록 골랐다
     */
    public static final int SOURCE_TITLE_MAX = 255;

    /**
     * 출처 제목이 {@value #SOURCE_TITLE_MAX}자를 넘으면 앞 {@value #SOURCE_TITLE_MAX}자만 담는다. 발행 기록에 저장되는 본문이
     * Kafka 메시지 크기 한도를 넘어 외부화가 영영 실패하는 일을 막는다(PR #873 리뷰, BD-163). 서로게이트 쌍을 반으로 자르지 않도록
     * code point로 센다. 지급 요청 값과 지급 결과에는 영향이 없다
     */
    public PayoutCompletedEvent {
        if (sourceTitle != null && sourceTitle.codePointCount(0, sourceTitle.length()) > SOURCE_TITLE_MAX) {
            sourceTitle = sourceTitle.substring(0, sourceTitle.offsetByCodePoints(0, SOURCE_TITLE_MAX));
        }
    }

    /** 출처 제목 없이 만든다 (#873 이전 형식과 같다) */
    public PayoutCompletedEvent(Long settlementId, Long receiverUserId, PayoutType payoutType,
            SettlementSourceType sourceType, Long sourceId, long paidAmountHp, Instant paidAt) {
        this(settlementId, receiverUserId, payoutType, sourceType, sourceId, paidAmountHp, paidAt, null);
    }
}
