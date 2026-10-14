package com.hapbang.appearance.app.dto;

import java.time.LocalDateTime;

import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** 출연 모집글 목록의 한 줄 (콘텐츠 설명·채널 URL 제외) */
public record AppearanceRecruitSummaryResDto(
        Long id,
        Long hostId,
        String title,
        @Schema(description = "방송 예정 일시 (KST)") LocalDateTime scheduledAt,
        @Schema(description = "1인당 출연료 (HP)") long feePerPerson,
        @Schema(description = "모집 인원") int recruitCount,
        @Schema(description = "현재 성립 인원") int selectedCount,
        @Schema(description = "모집 마감 일시 (KST)") LocalDateTime recruitDeadline,
        AppearanceRecruitStatus status,
        LocalDateTime createdAt
) {

    public static AppearanceRecruitSummaryResDto from(AppearanceRecruit recruit) {
        return new AppearanceRecruitSummaryResDto(
                recruit.getId(),
                recruit.getHostId(),
                recruit.getTitle(),
                recruit.getScheduledAt(),
                recruit.getFeePerPerson(),
                recruit.getRecruitCount(),
                recruit.getSelectedCount(),
                recruit.getRecruitDeadline(),
                recruit.getStatus(),
                recruit.getCreatedAt());
    }
}
