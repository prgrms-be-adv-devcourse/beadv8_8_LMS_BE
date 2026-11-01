package com.hapbang.appearance.app;

import com.hapbang.appearance.app.dto.AppearanceUserBackfillResDto;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;

/**
 * 회원 내부 API 장애·계약 위반으로 관리자 Backfill 실행 전체가 멈췄다 (#394). 이미 반영한 출연 회원은 되돌리지 않는다.
 * 응답(ProblemDetail)에 멈추기 전까지의 집계와 이어서 실행할 afterId({@code resumeAfterId})를 더한다. 회원 정보는 넣지 않는다.
 */
public class AppearanceUserBackfillException extends AppearanceException {

    AppearanceUserBackfillException(AppearanceErrorCode errorCode, AppearanceUserBackfillResDto progress,
            long resumeAfterId, Throwable cause) {
        super(errorCode, cause);
        addProperty("scanned", progress.scanned());
        addProperty("created", progress.created());
        addProperty("updated", progress.updated());
        addProperty("ignored", progress.ignored());
        addProperty("failed", progress.failed());
        addProperty("failedIds", progress.failedIds());
        addProperty("lastProcessedId", progress.lastProcessedId());
        addProperty("resumeAfterId", resumeAfterId);
    }
}
