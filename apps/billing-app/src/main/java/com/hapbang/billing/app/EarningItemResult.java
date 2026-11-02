package com.hapbang.billing.app;

import java.time.Instant;
import java.util.Comparator;

import com.hapbang.billing.domain.EarningSourceType;
import com.hapbang.billing.domain.EarningStatus;
import com.hapbang.billing.domain.EarningType;

// 강의·출연 수익 한 건을 Billing 기준으로 맞춘 값이다 (#295). 두 출처의 원본 DTO는 밖으로 내보내지 않는다.
// 시각은 모두 실제 시점(Instant)이다: 강의는 offset 시각, 출연은 KST LocalDateTime을 바꾼 값이다. 지급 예정 시각은 없으면 null이다.
public record EarningItemResult(
        EarningSourceType sourceType,
        Long sourceId,
        EarningType earningType,
        String title,
        long amountHp,
        EarningStatus status,
        Instant payoutScheduledAt,
        Instant statusUpdatedAt
) {

    // 통합 목록의 전역 순서: 상태 변경 시각 최신순 → sourceId 내림차순 → sourceType 이름 오름차순 → earningType 이름 오름차순.
    // 두 출처가 각각 (상태 변경 시각 desc, ID desc)로 주므로, 출처마다 앞쪽 N건만 받아 합쳐도 전역 앞쪽 N건이 정확하다.
    static final Comparator<EarningItemResult> ORDER = Comparator
            .comparing(EarningItemResult::statusUpdatedAt, Comparator.reverseOrder())
            .thenComparing(EarningItemResult::sourceId, Comparator.reverseOrder())
            .thenComparing(item -> item.sourceType().name())
            .thenComparing(item -> item.earningType().name());
}
