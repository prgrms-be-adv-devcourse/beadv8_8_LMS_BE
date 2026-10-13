package com.hapbang.lecture.domain;

public enum ContentType {
    VIDEO(FileType.LECTURE_VIDEO),
    DOCUMENT(FileType.LECTURE_DOCUMENT);

    private final FileType fileType;

    ContentType(FileType fileType) {
        this.fileType = fileType;
    }

    /** 이 콘텐츠에 연결할 수 있는 파일 용도 */
    public FileType fileType() {
        return fileType;
    }
}
