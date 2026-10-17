package com.hapbang.billing.in;

import java.time.Instant;

import com.hapbang.billing.app.EarningItemResult;
import com.hapbang.billing.domain.EarningSourceType;
import com.hapbang.billing.domain.EarningStatus;
import com.hapbang.billing.domain.EarningType;

// 지급 예정 수익 한 건의 응답 (#295). 시각은 UTC Instant이고, 지급 예정 시각을 정할 수 없으면 payoutScheduledAt은 null이다.
public record EarningItemResponse(
        EarningSourceType sourceType,
        Long sourceId,
        EarningType earningType,
        String title,
        long amountHp,
        EarningStatus status,
        Instant payoutScheduledAt,
        Instant statusUpdatedAt
) {

    public static EarningItemResponse from(EarningItemResult result) {
        return new EarningItemResponse(result.sourceType(), result.sourceId(), result.earningType(), result.title(),
                result.amountHp(), result.status(), result.payoutScheduledAt(), result.statusUpdatedAt());
    }
}
