package com.hapbang.appearance.app.dto;

import java.time.LocalDateTime;

import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceSourceType;
import com.hapbang.appearance.domain.AppearanceStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** 출연 목록의 한 줄 */
public record AppearanceSummaryResDto(
        Long id,
        @Schema(description = "출처: REQUEST 출연 요청 / RECRUIT 출연 모집") AppearanceSourceType sourceType,
        @Schema(description = "출처 출연 요청서 ID (요청에서 성립한 경우)") Long requestId,
        @Schema(description = "출처 모집글 ID (모집에서 성립한 경우)") Long recruitId,
        @Schema(description = "출처 요청서·모집글 제목") String title,
        Long hostId,
        Long guestId,
        @Schema(description = "출연료 (HP, 성립 시점 값)") long fee,
        @Schema(description = "방송 예정 일시 (KST, 성립 시점 값)") LocalDateTime scheduledAt,
        AppearanceStatus status,
        @Schema(description = "성립 시각") LocalDateTime establishedAt
) {

    public static AppearanceSummaryResDto from(Appearance appearance, String title) {
        return new AppearanceSummaryResDto(appearance.getId(), appearance.getSourceType(), appearance.getRequestId(),
                appearance.getRecruitId(), title, appearance.getHostId(), appearance.getGuestId(), appearance.getFee(),
                appearance.getScheduledAt(), appearance.getStatus(), appearance.getEstablishedAt());
    }
}
