package com.hapbang.lecture.domain;

import com.hapbang.lecture.global.exception.RetryLater;

/** 앱 전체 동시 업로드가 상한에 닿아 본문을 받지 않았다 (D81). 공통 핸들러가 503 {@code FILE_UPLOAD_BUSY}와 {@code Retry-After}로 응답한다 */
public class FileUploadBusyException extends LectureException implements RetryLater {

    private final long retryAfterSeconds;

    /** @param retryAfterSeconds 다시 올려 볼 때까지 기다릴 초(1 이상) */
    public FileUploadBusyException(long retryAfterSeconds) {
        super(LectureErrorCode.FILE_UPLOAD_BUSY);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
