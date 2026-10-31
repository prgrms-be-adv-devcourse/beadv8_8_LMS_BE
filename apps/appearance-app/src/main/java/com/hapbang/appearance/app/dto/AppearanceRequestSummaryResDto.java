package com.hapbang.appearance.app.dto;

import java.time.LocalDateTime;

import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceRequestStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** 출연 요청서 목록의 한 줄 (콘텐츠 설명·채널 URL 제외) */
public record AppearanceRequestSummaryResDto(
        Long id,
        Long hostId,
        Long guestId,
        String title,
        @Schema(description = "방송 예정 일시 (KST)") LocalDateTime scheduledAt,
        @Schema(description = "출연료 (HP)") long fee,
        @Schema(description = "수락 기한 (KST)") LocalDateTime acceptDeadline,
        AppearanceRequestStatus status,
        LocalDateTime createdAt
) {

    public static AppearanceRequestSummaryResDto from(AppearanceRequest request) {
        return new AppearanceRequestSummaryResDto(request.getId(), request.getHostId(), request.getGuestId(),
                request.getTitle(), request.getScheduledAt(), request.getFee(), request.getAcceptDeadline(),
                request.getStatus(), request.getCreatedAt());
    }
}
