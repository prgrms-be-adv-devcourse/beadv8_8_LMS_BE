package com.hapbang.lecture.in;

import com.hapbang.lecture.in.dto.FileUploadRequest;
import com.hapbang.lecture.in.dto.FileUploadResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 강의 파일 업로드 API Swagger 명세 (설계 8.3). 매핑은 {@link ApiV1LectureFileController} */
@Tag(name = "강의 파일", description = "썸네일·영상·자료의 S3 Presigned URL 업로드")
public interface ApiV1LectureFileControllerDocs {

    @Operation(summary = "업로드 URL 발급",
            description = "인증된 인플루언서. 파일 메타데이터를 PENDING으로 만들고 짧은 만료의 Presigned PUT URL을 준다.")
    FileUploadResponse createUploadUrl(Long userId, FileUploadRequest request);

    @Operation(summary = "업로드 완료",
            description = "업로더 본인. S3에 객체가 있는지 확인하고 UPLOADED로 바꾼다. UPLOADED 파일만 강의에 연결할 수 있다.")
    void complete(Long userId, Long fileId);
}
