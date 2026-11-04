package com.hapbang.lecture.domain;

import java.util.Set;

/**
 * S3에 올리는 강의 파일의 용도와 용도별 허용 형식·최대 크기 (D19).
 * 등록할 때 신고한 크기를 이 값으로 검사하고, 본문은 신고한 크기(Content-Length)와 같아야 받는다(D24).
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

    /** 등록할 때 받은 Content-Type을 그대로 비교한다. 본문을 올릴 때의 비교는 {@link FileResource#validateUploadable}이 한다 */
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
