package com.hapbang.appearance.app.dto;

import java.time.LocalDateTime;

import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceRequestStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** 출연 요청서 상세 */
public record AppearanceRequestResDto(
        Long id,
        Long hostId,
        Long guestId,
        String title,
        String hostChannelUrl,
        @Schema(description = "방송 예정 일시 (KST)") LocalDateTime scheduledAt,
        @Schema(description = "예상 출연 시간(분)") int expectedMinutes,
        @Schema(description = "콘텐츠 설명") String content,
        @Schema(description = "출연료 (HP)") long fee,
        @Schema(description = "수락 기한 (KST)") LocalDateTime acceptDeadline,
        AppearanceRequestStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static AppearanceRequestResDto from(AppearanceRequest request) {
        return new AppearanceRequestResDto(request.getId(), request.getHostId(), request.getGuestId(),
                request.getTitle(), request.getHostChannelUrl(), request.getScheduledAt(),
                request.getExpectedMinutes(), request.getContent(), request.getFee(), request.getAcceptDeadline(),
                request.getStatus(), request.getCreatedAt(), request.getUpdatedAt());
    }
}
