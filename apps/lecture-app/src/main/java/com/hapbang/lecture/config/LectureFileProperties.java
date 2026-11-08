package com.hapbang.lecture.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 강의 파일 설정 ({@code lecture.file.*}). 값을 주지 않으면 기본값이다.
 *
 * @param uploadMaxConcurrent 앱 전체에서 동시에 진행할 수 있는 파일 본문 업로드 수(D81). 넘으면 503 {@code FILE_UPLOAD_BUSY}. 기본 20
 */
@ConfigurationProperties("lecture.file")
public record LectureFileProperties(Integer uploadMaxConcurrent) {

    public LectureFileProperties {
        uploadMaxConcurrent = uploadMaxConcurrent == null ? 20 : uploadMaxConcurrent;
        if (uploadMaxConcurrent < 1) {
            throw new IllegalArgumentException("lecture.file.upload-max-concurrent는 1 이상이어야 합니다.");
        }
    }
}
