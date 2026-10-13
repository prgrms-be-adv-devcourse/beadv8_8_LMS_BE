package com.hapbang.lecture.domain;

import java.util.Set;

/**
 * S3에 올리는 강의 파일의 용도와 용도별 허용 형식·최대 크기 (D19).
 * 업로드 URL은 크기를 서명하지 않으므로 발급할 때 신고한 크기와 완료할 때 실제 크기를 모두 이 값으로 검사한다.
 */
public enum FileType {
    LECTURE_THUMBNAIL("썸네일", Set.of("image/jpeg", "image/png", "image/webp"), 5 * Sizes.MB),
    LECTURE_VIDEO("영상", Set.of("video/mp4"), 2 * Sizes.GB),
    LECTURE_DOCUMENT("자료", Set.of("application/pdf"), 50 * Sizes.MB);

    private final String description;
    private final Set<String> allowedContentTypes;
    private final long maxSize;

    FileType(String description, Set<String> allowedContentTypes, long maxSize) {
        this.description = description;
        this.allowedContentTypes = allowedContentTypes;
        this.maxSize = maxSize;
    }

    /** 오류 메시지용 이름 */
    public String description() {
        return description;
    }

    /** Content-Type은 업로드 URL 서명에 들어가므로 대소문자까지 그대로 비교한다 */
    public boolean allows(String contentType) {
        return allowedContentTypes.contains(contentType);
    }

    /** 바이트 */
    public long maxSize() {
        return maxSize;
    }

    private static final class Sizes {
        private static final long MB = 1024L * 1024L;
        private static final long GB = 1024L * MB;
    }
}
