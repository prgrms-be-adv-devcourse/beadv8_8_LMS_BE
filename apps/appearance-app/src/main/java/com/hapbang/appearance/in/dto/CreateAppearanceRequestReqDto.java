package com.hapbang.appearance.in.dto;

import java.time.LocalDateTime;

import com.hapbang.appearance.domain.AppearanceRequestContent;
import com.hapbang.appearance.domain.AppearanceContentRules;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 출연 요청서 보내기 요청. 형식만 여기서 검사하고, 최저 출연료·기한 순서·자기 자신 여부는 도메인에서 검사한다.
 */
public record CreateAppearanceRequestReqDto(
        @Schema(description = "게스트 회원 ID", example = "2")
        @NotNull(message = "게스트 ID는 필수입니다.")
        @Positive(message = "게스트 ID는 1 이상이어야 합니다.")
        Long guestId,

        @Schema(example = "주말 게임 합방 출연 요청")
        @NotBlank(message = "제목은 필수입니다.")
        @Size(max = AppearanceContentRules.TITLE_MAX, message = "제목은 100자 이하여야 합니다.")
        String title,

        @Schema(example = "https://www.youtube.com/@host")
        @NotBlank(message = "호스트 채널 URL은 필수입니다.")
        @Size(max = AppearanceContentRules.URL_MAX, message = "호스트 채널 URL은 255자 이하여야 합니다.")
        @Pattern(regexp = AppearanceContentRules.URL_PATTERN, message = "호스트 채널 URL은 http:// 또는 https://로 시작해야 합니다.")
        String hostChannelUrl,

        @Schema(description = "방송 예정 일시 (KST)", example = "2026-10-20T21:00:00")
        @NotNull(message = "방송 예정 일시는 필수입니다.")
        LocalDateTime scheduledAt,

        @Schema(description = "예상 출연 시간(분)", example = "60")
        @NotNull(message = "예상 출연 시간은 필수입니다.")
        @Positive(message = "예상 출연 시간은 1분 이상이어야 합니다.")
        @Max(value = AppearanceContentRules.EXPECTED_MINUTES_MAX, message = "예상 출연 시간은 1440분(24시간) 이하여야 합니다.")
        Integer expectedMinutes,

        @Schema(description = "콘텐츠 설명")
        @NotBlank(message = "콘텐츠 설명은 필수입니다.")
        @Size(max = AppearanceContentRules.CONTENT_MAX, message = "콘텐츠 설명은 5000자 이하여야 합니다.")
        String content,

        @Schema(description = "출연료 (HP). 최저 출연료 이상", example = "30000")
        @NotNull(message = "출연료는 필수입니다.")
        @Positive(message = "출연료는 1 이상이어야 합니다.")
        Long fee,

        @Schema(description = "수락 기한 (KST). 현재 이후, 방송 예정 일시 이전", example = "2026-10-18T23:59:00")
        @NotNull(message = "수락 기한은 필수입니다.")
        LocalDateTime acceptDeadline
) {

    public AppearanceRequestContent toContent() {
        return new AppearanceRequestContent(title, hostChannelUrl, scheduledAt, expectedMinutes, content, fee,
                acceptDeadline);
    }
}
