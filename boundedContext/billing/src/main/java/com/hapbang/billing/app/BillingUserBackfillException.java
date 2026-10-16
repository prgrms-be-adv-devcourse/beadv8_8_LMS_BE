package com.hapbang.billing.app;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;

// 회원 내부 API 장애·계약 위반으로 Backfill 실행 전체가 멈췄다 (#324). 이미 반영한 회원은 되돌리지 않는다.
// 응답에 멈추기 전까지의 집계와 이어서 실행할 afterId(resumeAfterId)를 더한다. 회원 정보·내부 API 키는 넣지 않는다.
public class BillingUserBackfillException extends BillingException {

    private final transient BillingUserBackfillResult progress;

    BillingUserBackfillException(BillingErrorCode errorCode, BillingUserBackfillResult progress, long resumeAfterId,
            Throwable cause) {
        super(errorCode, cause);
        this.progress = progress;
        addProperty("scanned", progress.scanned());
        addProperty("created", progress.created());
        addProperty("updated", progress.updated());
        addProperty("ignoredStale", progress.ignoredStale());
        addProperty("ignoredVersionless", progress.ignoredVersionless());
        addProperty("failed", progress.failed());
        addProperty("failedIds", progress.failedIds());
        addProperty("lastProcessedId", progress.lastProcessedId());
        addProperty("resumeAfterId", resumeAfterId);
    }

    // 멈추기 전까지의 집계(completed = false, nextAfterId = 이어서 실행할 afterId)
    public BillingUserBackfillResult getProgress() {
        return progress;
    }
}
