package com.hapbang.chat.app.exception;

import com.hapbang.chat.app.dto.ChatUserBackfillResult;

/**
 * 회원 원본 조회 장애·계약 위반으로 백필 실행 전체가 멈췄다. 이미 반영한 회원은 되돌리지 않는다.
 * 응답(ProblemDetail)에 멈추기 전까지의 집계와 이어서 실행할 afterId({@code resumeAfterId})를 더한다. 회원 정보·내부 API 키는 넣지 않는다.
 */
public class ChatUserBackfillException extends ChatException {

    private final transient ChatUserBackfillResult progress;

    public ChatUserBackfillException(ChatErrorCode errorCode, ChatUserBackfillResult progress, long resumeAfterId) {
        super(errorCode);
        this.progress = progress;
        addProperty("scanned", progress.scanned());
        addProperty("created", progress.created());
        addProperty("updated", progress.updated());
        addProperty("skippedStale", progress.skippedStale());
        addProperty("failed", progress.failed());
        addProperty("failedIds", progress.failedIds());
        addProperty("lastProcessedId", progress.lastProcessedId());
        addProperty("resumeAfterId", resumeAfterId);
    }

    /** 멈추기 전까지의 집계 (completed = false, nextAfterId = 이어서 실행할 afterId) */
    public ChatUserBackfillResult getProgress() {
        return progress;
    }
}
