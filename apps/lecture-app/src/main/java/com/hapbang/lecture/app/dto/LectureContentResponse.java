package com.hapbang.lecture.app.dto;

import com.hapbang.lecture.domain.ContentType;
import com.hapbang.lecture.domain.LectureContent;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "강의 콘텐츠. 파일 URL은 /access로 따로 발급받는다.")
public record LectureContentResponse(
        Long contentId,
        @Schema(description = "연결된 파일 ID. 판매자·관리자에게만 내려준다. 콘텐츠 교체에서 바꾸지 않는 강은 이 값을 다시 보낸다 (D53)",
                nullable = true)
        Long fileId,
        String title,
        int sequence,
        ContentType contentType
) {

    public static LectureContentResponse from(LectureContent content, boolean showFileId) {
        return new LectureContentResponse(content.getId(), showFileId ? content.getFileId() : null,
                content.getTitle(), content.getSequence(), content.getContentType());
    }
}
