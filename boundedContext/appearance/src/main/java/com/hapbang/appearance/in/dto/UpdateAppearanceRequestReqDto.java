package com.hapbang.appearance.in.dto;

import com.hapbang.appearance.domain.AppearanceContentRules;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 출연 요청서 기본 정보 수정 요청. 보낸 값만 바꾸고, 보내지 않은(null) 값은 그대로 둔다. 하나 이상은 보내야 한다.
 */
public record UpdateAppearanceRequestReqDto(
        @Schema(description = "제목. 보내지 않으면 그대로")
        @Size(max = AppearanceContentRules.TITLE_MAX, message = "제목은 100자 이하여야 합니다.")
        @Pattern(regexp = AppearanceContentRules.NOT_BLANK_PATTERN, message = "제목은 공백일 수 없습니다.")
        String title,

        @Schema(description = "콘텐츠 설명. 보내지 않으면 그대로")
        @Size(max = AppearanceContentRules.CONTENT_MAX, message = "콘텐츠 설명은 5000자 이하여야 합니다.")
        @Pattern(regexp = AppearanceContentRules.NOT_BLANK_PATTERN, message = "콘텐츠 설명은 공백일 수 없습니다.")
        String content,

        @Schema(description = "호스트 채널 URL. 보내지 않으면 그대로")
        @Size(max = AppearanceContentRules.URL_MAX, message = "호스트 채널 URL은 255자 이하여야 합니다.")
        @Pattern(regexp = AppearanceContentRules.URL_PATTERN, message = "호스트 채널 URL은 http:// 또는 https://로 시작해야 합니다.")
        String hostChannelUrl
) {

    @Schema(hidden = true)
    @AssertTrue(message = "수정할 값을 하나 이상 보내야 합니다.")
    public boolean isAnyFieldPresent() {
        return title != null || content != null || hostChannelUrl != null;
    }
}
