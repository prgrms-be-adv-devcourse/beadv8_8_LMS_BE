package com.hapbang.billing.in;

import java.time.Instant;

import com.hapbang.billing.app.EarningSummaryResult;

// 지급 예정 수익 통합 요약 응답 (#295). 상태별·전체 금액(HP)과 건수이며 출처별 소계는 없다. asOf는 Billing 조회 시작 시각(UTC)이다.
public record EarningSummaryResponse(
        AmountResponse confirmationPending,
        AmountResponse scheduled,
        AmountResponse onHold,
        AmountResponse processing,
        AmountResponse failed,
        AmountResponse total,
        Instant asOf
) {

    public static EarningSummaryResponse from(EarningSummaryResult result) {
        return new EarningSummaryResponse(AmountResponse.from(result.confirmationPending()),
                AmountResponse.from(result.scheduled()), AmountResponse.from(result.onHold()),
                AmountResponse.from(result.processing()), AmountResponse.from(result.failed()),
                AmountResponse.from(result.total()), result.asOf());
    }

    public record AmountResponse(long amountHp, long count) {

        static AmountResponse from(EarningSummaryResult.Amount amount) {
            return new AmountResponse(amount.amountHp(), amount.count());
        }
    }
}
