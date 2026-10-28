package com.hapbang.billing.app;

import java.util.List;

// 기존 회원 BillingUser Backfill 한 번의 실행 결과 (#324).
// scanned = created + updated + ignoredStale + ignoredVersionless + failed. failedIds는 앞쪽 최대 100개만 담고 failed는 전체 건수다.
// completed는 회원 목록을 끝까지 순회했다는 뜻이며, 모든 회원이 성공했는지는 failed로 판단한다.
// 끝나지 않았으면(completed = false) nextAfterId를 다음 실행의 afterId로 넘겨 이어서 실행한다.
public record BillingUserBackfillResult(
        long scanned,
        long created,
        long updated,
        long ignoredStale,
        long ignoredVersionless,
        long failed,
        List<Long> failedIds,
        Long lastProcessedId,
        Long nextAfterId,
        boolean completed
) {

    public BillingUserBackfillResult {
        failedIds = List.copyOf(failedIds);
    }
}
