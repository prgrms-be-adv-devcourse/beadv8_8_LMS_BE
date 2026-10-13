package com.hapbang.lecture.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 강의 콘텐츠 종류. 종류마다 연결할 수 있는 파일 용도가 정해져 있다.
 */
@Schema(description = """
        강의 콘텐츠 종류
        - `VIDEO`: 영상(mp4)
        - `DOCUMENT`: 자료(pdf)""")
public enum ContentType {
    /** 영상. {@link FileType#LECTURE_VIDEO} 파일을 연결한다 */
    VIDEO(FileType.LECTURE_VIDEO),
    /** 자료. {@link FileType#LECTURE_DOCUMENT} 파일을 연결한다 */
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
