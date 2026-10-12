package com.hapbang.lecture.in;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.global.auth.LoginUserId;
import com.hapbang.lecture.in.dto.FileUploadRequest;
import com.hapbang.lecture.in.dto.FileUploadResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** 강의 파일 업로드 API (설계 8.3) */
// TODO: 13장 5단계(S3 연동)에서 구현한다.
// TODO(security): 인증된 인플루언서만 허용한다(설계 9장). global SecurityConfig와 함께 추가한다.
@Tag(name = "강의 파일", description = "썸네일·영상·자료의 S3 Presigned URL 업로드")
@RestController
@RequestMapping("/api/v1/lectures/files")
public class ApiV1LectureFileController {

    @Operation(summary = "업로드 URL 발급",
            description = "인증된 인플루언서. 파일 메타데이터를 PENDING으로 만들고 짧은 만료의 Presigned PUT URL을 준다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FileUploadResponse createUploadUrl(@LoginUserId Long userId,
            @Valid @RequestBody FileUploadRequest request) {
        throw NotImplemented.yet();
    }

    @Operation(summary = "업로드 완료",
            description = "업로더 본인. S3에 객체가 있는지 확인하고 UPLOADED로 바꾼다. UPLOADED 파일만 강의에 연결할 수 있다.")
    @PostMapping("/{fileId}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void complete(@LoginUserId Long userId, @PathVariable("fileId") Long fileId) {
        throw NotImplemented.yet();
    }
}
