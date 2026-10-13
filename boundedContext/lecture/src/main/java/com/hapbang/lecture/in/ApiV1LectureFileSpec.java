package com.hapbang.lecture.in;

import java.io.IOException;

import org.springframework.http.ResponseEntity;

import com.hapbang.global.response.RsData;
import com.hapbang.lecture.app.dto.FileUploadResponse;
import com.hapbang.lecture.in.dto.FileUploadRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/** 강의 파일 업로드 API Swagger 명세 (설계 8.3). 매핑은 {@link ApiV1LectureFileController} */
@Tag(name = "강의 파일", description = "썸네일·영상·자료 업로드. 본문은 서버를 거쳐 S3에 저장한다")
public interface ApiV1LectureFileSpec {

    @Operation(summary = "파일 정보 등록",
            description = "인증된 인플루언서. 파일 메타데이터를 PENDING으로 만들고 fileId를 준다. 본문은 PUT /{fileId}/content로 올린다. "
                    + "허용하지 않는 형식은 UNSUPPORTED_FILE_CONTENT_TYPE, 최대 크기 초과는 FILE_TOO_LARGE(400).")
    RsData<FileUploadResponse> register(Long userId, FileUploadRequest request);

    @Operation(summary = "파일 본문 업로드",
            description = "업로더 본인. 요청 본문이 파일 그대로다(multipart·form 아님). 서버가 S3에 저장하고 UPLOADED로 바꾼다(204). "
                    + "Content-Type은 등록한 형식(대소문자·파라미터 무시), Content-Length는 등록한 크기와 같아야 한다. "
                    + "없는 파일 404 FILE_NOT_FOUND, 본인이 아님 403 NOT_FILE_UPLOADER, 이미 올림 409 FILE_ALREADY_UPLOADED, "
                    + "Content-Length 없음 411 FILE_LENGTH_REQUIRED, 크기 다름 400 FILE_SIZE_MISMATCH, "
                    + "형식 다름 400 FILE_CONTENT_TYPE_MISMATCH. 실패하거나 끊기면 PENDING으로 남아 처음부터 다시 올린다.",
            requestBody = @RequestBody(required = true, content = {
                    @Content(mediaType = "video/mp4", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "image/webp", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary"))
            }))
    ResponseEntity<Void> upload(Long userId, Long fileId, HttpServletRequest request) throws IOException;
}
