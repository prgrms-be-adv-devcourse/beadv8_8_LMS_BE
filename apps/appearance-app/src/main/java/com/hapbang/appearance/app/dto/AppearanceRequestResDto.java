package com.hapbang.appearance.app.dto;

import java.time.LocalDateTime;

import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceRequestStatus;
import com.hapbang.appearance.domain.HpTaskStatus;

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
        @Schema(description = "요청서 버전. 수락할 때 그대로 보낸다 (그사이 수정됐으면 수락이 막힌다)") Long version,
        @Schema(description = "거절 사유 (거절된 경우, 선택 입력)") String rejectReason,
        @Schema(description = "게스트 응답(수락·거절) 시각") LocalDateTime respondedAt,
        @Schema(description = "호스트 응답 전 취소 시각") LocalDateTime canceledAt,
        @Schema(description = "성립한 출연 ID (수락된 경우)") Long appearanceId,
        @Schema(description = "보류 HP 반환 진행 상태 (거절·취소된 경우): PENDING 처리 중 / DONE 완료 / DEAD 지연(고객센터 확인)")
        HpTaskStatus hpReleaseStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /** 출연·반환 정보 없이 요청서만 담는다 (보내기·수정처럼 아직 응답 전인 경우) */
    public static AppearanceRequestResDto from(AppearanceRequest request) {
        return from(request, null, null);
    }

    public static AppearanceRequestResDto from(AppearanceRequest request, Long appearanceId,
                                               HpTaskStatus hpReleaseStatus) {
        return new AppearanceRequestResDto(request.getId(), request.getHostId(), request.getGuestId(),
                request.getTitle(), request.getHostChannelUrl(), request.getScheduledAt(),
                request.getExpectedMinutes(), request.getContent(), request.getFee(), request.getAcceptDeadline(),
                request.getStatus(), request.getVersion(), request.getRejectReason(), request.getRespondedAt(),
                request.getCanceledAt(), appearanceId, hpReleaseStatus, request.getCreatedAt(),
                request.getUpdatedAt());
    }
}
