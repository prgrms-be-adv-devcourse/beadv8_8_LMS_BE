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

import jakarta.validation.Valid;

/** 강의 파일 업로드 API (설계 8.3). Swagger 명세는 {@link ApiV1LectureFileControllerDocs} */
// TODO: 13장 5단계(S3 연동)에서 구현한다.
// TODO(security): 인증된 인플루언서만 허용한다(설계 9장). global SecurityConfig와 함께 추가한다.
@RestController
@RequestMapping("/api/v1/lectures/files")
public class ApiV1LectureFileController implements ApiV1LectureFileControllerDocs {

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FileUploadResponse createUploadUrl(@LoginUserId Long userId,
            @Valid @RequestBody FileUploadRequest request) {
        throw NotImplemented.yet();
    }

    @Override
    @PostMapping("/{fileId}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void complete(@LoginUserId Long userId, @PathVariable("fileId") Long fileId) {
        throw NotImplemented.yet();
    }
}
