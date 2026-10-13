package com.hapbang.lecture.in;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.global.auth.LoginUserId;
import com.hapbang.lecture.app.LectureFileFacade;
import com.hapbang.lecture.app.dto.FileUploadResponse;
import com.hapbang.lecture.in.dto.FileUploadRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 강의 파일 업로드 API (설계 8.3). Swagger 명세는 {@link ApiV1LectureFileSpec} */
@RestController
@RequestMapping("/api/v1/lectures/files")
@RequiredArgsConstructor
public class ApiV1LectureFileController implements ApiV1LectureFileSpec {

    private final LectureFileFacade lectureFileFacade;

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FileUploadResponse createUploadUrl(@LoginUserId Long userId,
            @Valid @RequestBody FileUploadRequest request) {
        return lectureFileFacade.createUploadUrl(userId, request.fileType(), request.originalFilename(),
                request.contentType(), request.fileSize());
    }

    @Override
    @PostMapping("/{fileId}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void complete(@LoginUserId Long userId, @PathVariable("fileId") Long fileId) {
        lectureFileFacade.complete(userId, fileId);
    }
}
