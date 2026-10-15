package com.hapbang.appearance.app.dto;

import java.time.LocalDateTime;

import com.hapbang.appearance.domain.AppearanceApplication;
import com.hapbang.appearance.domain.AppearanceApplicationStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** 출연 모집 지원 (지원·취소·수락·거절 응답과 지원 목록의 한 줄) */
public record AppearanceApplicationResDto(
        Long id,
        @Schema(description = "지원한 모집글 ID") Long recruitId,
        @Schema(description = "지원한 게스트 회원 ID") Long guestId,
        @Schema(description = "지원 메시지 (선택)") String message,
        AppearanceApplicationStatus status,
        @Schema(description = "호스트 응답(수락·거절) 시각") LocalDateTime respondedAt,
        @Schema(description = "게스트 지원 취소 시각") LocalDateTime withdrawnAt,
        @Schema(description = "성립한 출연 ID (수락된 경우)") Long appearanceId,
        LocalDateTime createdAt
) {

    public static AppearanceApplicationResDto from(AppearanceApplication application, Long appearanceId) {
        return new AppearanceApplicationResDto(
                application.getId(),
                application.getRecruitId(),
                application.getGuestId(),
                application.getMessage(),
                application.getStatus(),
                application.getRespondedAt(),
                application.getWithdrawnAt(),
                appearanceId,
                application.getCreatedAt());
    }
}
