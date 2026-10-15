package com.hapbang.appearance.app.dto;

import java.time.LocalDateTime;

import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRecruitCloseType;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;
import com.hapbang.appearance.domain.HpTaskStatus;

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
        @Schema(description = "지원자 수: 게스트가 취소한 지원만 뺀 전체 지원 (대기·수락·미선정)") long applicantCount,
        @Schema(description = "모집 마감 일시 (KST)") LocalDateTime recruitDeadline,
        AppearanceRecruitStatus status,
        @Schema(description = "마감 방식 (마감된 경우): DEADLINE 기한 도래 / EARLY 조기 마감") AppearanceRecruitCloseType closeType,
        @Schema(description = "마감 시각 (마감된 경우)") LocalDateTime closedAt,
        @Schema(description = "모집 전체 취소 시각 (취소된 경우)") LocalDateTime canceledAt,
        @Schema(description = "마감 반환(성립하지 못한 인원분) 진행 상태. 호스트에게만 보이며, 반환할 금액이 없거나 마감 전이면 null. "
                + "PENDING 처리 중 / DONE 완료 / DEAD 실패(고객센터 확인)") HpTaskStatus hpReleaseStatus,
        @Schema(description = "모집글 버전") Long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static AppearanceRecruitResDto from(AppearanceRecruit recruit, long applicantCount) {
        return from(recruit, applicantCount, null);
    }

    public static AppearanceRecruitResDto from(AppearanceRecruit recruit, long applicantCount,
                                               HpTaskStatus hpReleaseStatus) {
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
                applicantCount,
                recruit.getRecruitDeadline(),
                recruit.getStatus(),
                recruit.getCloseType(),
                recruit.getClosedAt(),
                recruit.getCanceledAt(),
                hpReleaseStatus,
                recruit.getVersion(),
                recruit.getCreatedAt(),
                recruit.getUpdatedAt());
    }
}
