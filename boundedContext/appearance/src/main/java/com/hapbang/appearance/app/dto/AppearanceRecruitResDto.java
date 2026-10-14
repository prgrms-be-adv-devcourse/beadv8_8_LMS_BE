package com.hapbang.appearance.app.dto;

import java.time.LocalDateTime;

import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRecruitCloseType;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** 출연 모집글 상세 */
public record AppearanceRecruitResDto(
        Long id,
        Long hostId,
        String title,
        String hostChannelUrl,
        @Schema(description = "방송 예정 일시 (KST)") LocalDateTime scheduledAt,
        @Schema(description = "예상 출연 시간(분)") int expectedMinutes,
        @Schema(description = "콘텐츠 설명") String content,
        @Schema(description = "1인당 출연료 (HP)") long feePerPerson,
        @Schema(description = "모집 인원") int recruitCount,
        @Schema(description = "현재 성립 인원") int selectedCount,
        @Schema(description = "모집 마감 일시 (KST)") LocalDateTime recruitDeadline,
        AppearanceRecruitStatus status,
        @Schema(description = "마감 방식 (마감된 경우): DEADLINE 기한 도래 / EARLY 조기 마감") AppearanceRecruitCloseType closeType,
        @Schema(description = "마감 시각 (마감된 경우)") LocalDateTime closedAt,
        @Schema(description = "모집 전체 취소 시각 (취소된 경우)") LocalDateTime canceledAt,
        @Schema(description = "모집글 버전") Long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static AppearanceRecruitResDto from(AppearanceRecruit recruit) {
        return new AppearanceRecruitResDto(
                recruit.getId(),
                recruit.getHostId(),
                recruit.getTitle(),
                recruit.getHostChannelUrl(),
                recruit.getScheduledAt(),
                recruit.getExpectedMinutes(),
                recruit.getContent(),
                recruit.getFeePerPerson(),
                recruit.getRecruitCount(),
                recruit.getSelectedCount(),
                recruit.getRecruitDeadline(),
                recruit.getStatus(),
                recruit.getCloseType(),
                recruit.getClosedAt(),
                recruit.getCanceledAt(),
                recruit.getVersion(),
                recruit.getCreatedAt(),
                recruit.getUpdatedAt());
    }
}
