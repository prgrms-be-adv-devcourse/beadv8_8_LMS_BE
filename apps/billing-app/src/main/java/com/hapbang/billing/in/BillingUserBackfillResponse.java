package com.hapbang.billing.in;

import java.util.List;

import com.hapbang.billing.app.BillingUserBackfillResult;

// 기존 회원 BillingUser Backfill 실행 결과 응답 (#324). 회원 정보(닉네임 등)는 담지 않고 집계와 회원 ID만 담는다.
// scanned = created + updated + ignoredStale + ignoredVersionless + failed. failedIds는 최대 100개다.
// completed = false면 nextAfterId를 다음 실행의 afterId로 넘긴다. completed는 순회 완료일 뿐이며 성공 여부는 failed로 판단한다.
public record BillingUserBackfillResponse(
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

    public static BillingUserBackfillResponse from(BillingUserBackfillResult result) {
        return new BillingUserBackfillResponse(result.scanned(), result.created(), result.updated(),
                result.ignoredStale(), result.ignoredVersionless(), result.failed(), result.failedIds(),
                result.lastProcessedId(), result.nextAfterId(), result.completed());
    }
}
