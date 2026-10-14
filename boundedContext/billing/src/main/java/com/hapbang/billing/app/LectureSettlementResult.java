package com.hapbang.billing.app;

import java.time.Instant;

import com.hapbang.billing.domain.Settlement;
import com.hapbang.billing.domain.SettlementStatus;

// 강의 판매 수익 지급 예정 건의 등록·상태 변경 결과로, Billing 엔티티 대신 지급 건의 현재 값을 담는다.
// replayed가 true면 같은 요청을 다시 보내 아무것도 바꾸지 않은 것이다(같은 내용 재등록, 이미 그 상태인 건에 같은 상태 변경).
public record LectureSettlementResult(
        Long settlementId,
        Long orderItemId,
        Long holdId,
        Long receiverUserId,
        Long receiverWalletId,
        long amountHp,
        SettlementStatus status,
        Instant scheduledAt,
        boolean replayed
) {

    static LectureSettlementResult of(Settlement settlement, boolean replayed) {
        return new LectureSettlementResult(settlement.getId(), settlement.getSourceId(), settlement.getPointHoldId(),
                settlement.getReceiverUserId(), settlement.getReceiverWalletId(), settlement.getGrossAmountHp(),
                settlement.getStatus(), settlement.getScheduledAt(), replayed);
    }
}
