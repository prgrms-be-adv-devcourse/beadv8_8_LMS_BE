package com.hapbang.appearance.app.dto;

import java.time.LocalDateTime;

import com.hapbang.appearance.domain.AppearanceCancelRequest;
import com.hapbang.appearance.domain.AppearanceCancelRequestStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** 출연 취소 요청 1건 (출연 상세의 이력) */
public record AppearanceCancelRequestResDto(
        Long id,
        @Schema(description = "이 출연에서 몇 번째 취소 요청인지 (1 또는 2)") int sequence,
        @Schema(description = "취소 사유 (게스트)") String reason,
        @Schema(description = "REQUESTED 처리 중 / ACCEPTED 수락(합의 취소) / REJECTED 거절 / AUTO_REJECTED 방송 예정 일시까지 응답 없어 자동 거절")
        AppearanceCancelRequestStatus status,
        @Schema(description = "거절 사유 (호스트, 선택)") String rejectReason,
        @Schema(description = "요청 시각") LocalDateTime requestedAt,
        @Schema(description = "응답·자동 거절 시각") LocalDateTime respondedAt
) {

    public static AppearanceCancelRequestResDto from(AppearanceCancelRequest cancelRequest) {
        return new AppearanceCancelRequestResDto(cancelRequest.getId(), cancelRequest.getSequence(),
                cancelRequest.getReason(), cancelRequest.getStatus(), cancelRequest.getRejectReason(),
                cancelRequest.getCreatedAt(), cancelRequest.getRespondedAt());
    }
}
