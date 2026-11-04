package com.hapbang.appearance.app.dto;

import java.time.LocalDateTime;

import com.hapbang.appearance.domain.AppearanceApplication;
import com.hapbang.appearance.domain.AppearanceApplicationStatus;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 출연 모집 지원 (지원·취소·수락·거절 응답, 지원 목록의 한 줄, 모집글 상세의 내 지원).
 * 모집글 제목·방송 예정 일시·모집 상태는 지원 시점이 아닌 현재 모집글 값이다 (ISSUE.md APR-33).
 */
public record AppearanceApplicationResDto(
        Long id,
        @Schema(description = "지원한 모집글 ID") Long recruitId,
        @Schema(description = "모집글 제목 (현재 값, 모집글을 수정하면 따라 바뀐다)") String recruitTitle,
        @Schema(description = "모집글 방송 예정 일시 (KST)") LocalDateTime recruitScheduledAt,
        @Schema(description = "모집글 상태 (RECRUITING 모집 중 / CLOSED 마감 / CANCELED 모집 전체 취소)")
        AppearanceRecruitStatus recruitStatus,
        @Schema(description = "지원한 게스트 회원 ID") Long guestId,
        @Schema(description = "지원 메시지 (선택)") String message,
        AppearanceApplicationStatus status,
        @Schema(description = "호스트 응답(수락·거절) 시각") LocalDateTime respondedAt,
        @Schema(description = "게스트 지원 취소 시각") LocalDateTime withdrawnAt,
        @Schema(description = "성립한 출연 ID (수락된 경우)") Long appearanceId,
        LocalDateTime createdAt
) {

    /**
     * @param recruit 지원한 모집글 ({@code application.getRecruitId()}의 모집글)
     */
    public static AppearanceApplicationResDto from(AppearanceApplication application, AppearanceRecruit recruit,
                                                   Long appearanceId) {
        return new AppearanceApplicationResDto(
                application.getId(),
                application.getRecruitId(),
                recruit.getTitle(),
                recruit.getScheduledAt(),
                recruit.getStatus(),
                application.getGuestId(),
                application.getMessage(),
                application.getStatus(),
                application.getRespondedAt(),
                application.getWithdrawnAt(),
                appearanceId,
                application.getCreatedAt());
    }
}
