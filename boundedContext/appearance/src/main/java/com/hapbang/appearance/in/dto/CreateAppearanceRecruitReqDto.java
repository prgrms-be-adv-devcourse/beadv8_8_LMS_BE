package com.hapbang.appearance.in.dto;

import java.time.LocalDateTime;

import com.hapbang.appearance.domain.AppearanceRecruitContent;
import com.hapbang.appearance.domain.AppearanceRequestRules;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 출연 모집글 올리기 요청. 형식만 여기서 검사하고, 최저 출연료·모집 인원 범위·기한 순서는 도메인에서 검사한다.
 */
public record CreateAppearanceRecruitReqDto(
        @Schema(example = "주말 게임 합방 게스트 모집")
        @NotBlank(message = "제목은 필수입니다.")
        @Size(max = AppearanceRequestRules.TITLE_MAX, message = "제목은 100자 이하여야 합니다.")
        String title,

        @Schema(example = "https://www.youtube.com/@host")
        @NotBlank(message = "호스트 채널 URL은 필수입니다.")
        @Size(max = AppearanceRequestRules.URL_MAX, message = "호스트 채널 URL은 255자 이하여야 합니다.")
        @Pattern(regexp = AppearanceRequestRules.URL_PATTERN, message = "호스트 채널 URL은 http:// 또는 https://로 시작해야 합니다.")
        String hostChannelUrl,

        @Schema(description = "방송 예정 일시 (KST)", example = "2026-10-20T21:00:00")
        @NotNull(message = "방송 예정 일시는 필수입니다.")
        LocalDateTime scheduledAt,

        @Schema(description = "예상 출연 시간(분)", example = "60")
        @NotNull(message = "예상 출연 시간은 필수입니다.")
        @Positive(message = "예상 출연 시간은 1분 이상이어야 합니다.")
        @Max(value = AppearanceRequestRules.EXPECTED_MINUTES_MAX, message = "예상 출연 시간은 1440분(24시간) 이하여야 합니다.")
        Integer expectedMinutes,

        @Schema(description = "콘텐츠 설명")
        @NotBlank(message = "콘텐츠 설명은 필수입니다.")
        @Size(max = AppearanceRequestRules.CONTENT_MAX, message = "콘텐츠 설명은 5000자 이하여야 합니다.")
        String content,

        @Schema(description = "1인당 출연료 (HP). 최저 출연료 이상", example = "30000")
        @NotNull(message = "1인당 출연료는 필수입니다.")
        @Positive(message = "1인당 출연료는 1 이상이어야 합니다.")
        Long feePerPerson,

        @Schema(description = "모집 인원. 1명 이상 최대 모집 인원(10명) 이하", example = "3")
        @NotNull(message = "모집 인원은 필수입니다.")
        Integer recruitCount,

        @Schema(description = "모집 마감 일시 (KST). 현재 이후, 방송 예정 일시 이전", example = "2026-10-18T23:59:00")
        @NotNull(message = "모집 마감 일시는 필수입니다.")
        LocalDateTime recruitDeadline
) {

    public AppearanceRecruitContent toContent() {
        return new AppearanceRecruitContent(title, hostChannelUrl, scheduledAt, expectedMinutes, content,
                feePerPerson, recruitCount, recruitDeadline);
    }
}
