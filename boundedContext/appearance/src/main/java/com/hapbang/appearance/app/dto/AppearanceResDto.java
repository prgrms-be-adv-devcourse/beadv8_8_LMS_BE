package com.hapbang.appearance.app.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceSourceType;
import com.hapbang.appearance.domain.AppearanceStatus;
import com.hapbang.appearance.domain.HpTaskStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** 출연 상세 (호스트·게스트만) */
public record AppearanceResDto(
        Long id,
        @Schema(description = "출처: REQUEST 출연 요청 / RECRUIT 출연 모집") AppearanceSourceType sourceType,
        @Schema(description = "출처 출연 요청서 ID (요청에서 성립한 경우)") Long requestId,
        @Schema(description = "출처 모집글 ID (모집에서 성립한 경우)") Long recruitId,
        @Schema(description = "출처 모집 지원 ID (모집에서 성립한 경우)") Long applicationId,
        @Schema(description = "출처 요청서·모집글 제목") String title,
        Long hostId,
        Long guestId,
        @Schema(description = "출연료 (HP, 성립 시점 값)") long fee,
        @Schema(description = "방송 예정 일시 (KST, 성립 시점 값). 출연 취소 요청·응답은 이 시각 전까지") LocalDateTime scheduledAt,
        @Schema(description = "ESTABLISHED 성립 / CANCEL_REQUESTED 취소 요청 처리 중 / CANCELED_BY_AGREEMENT 합의 취소 / "
                + "그 밖은 출연 확인·호스트 일방 취소에서 쓴다") AppearanceStatus status,
        @Schema(description = "성립 시각") LocalDateTime establishedAt,
        @Schema(description = "취소 시각 (취소된 경우)") LocalDateTime canceledAt,
        @Schema(description = "출연 취소 요청 이력 (순번 순, 최대 2건)") List<AppearanceCancelRequestResDto> cancelRequests,
        @Schema(description = "이 출연의 반환(합의 취소) 진행 상태. 반환할 일이 없으면 null (모집글 마감 전 합의 취소는 보류 유지라 null). "
                + "PENDING 처리 중 / DONE 완료 / DEAD 실패(고객센터 확인)") HpTaskStatus hpReleaseStatus,
        @Schema(description = "호스트 일방 취소 위약금 (HP, 게스트에게 지급). 일방 취소된 경우만") Long penaltyAmount,
        @Schema(description = "호스트 일방 취소 사유 (선택). 모집 전체 취소면 모집글 취소 사유와 같다") String hostCancelReason,
        @Schema(description = "호스트 일방 취소 위약금 정산 진행 상태. 일방 취소 전이면 null. PENDING 처리 중 / DONE 완료 / DEAD 실패(고객센터 확인)")
        HpTaskStatus hpPenaltyStatus,
        @Schema(description = "출연 버전") Long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static AppearanceResDto from(Appearance appearance, String title,
                                        List<AppearanceCancelRequestResDto> cancelRequests,
                                        HpTaskStatus hpReleaseStatus, HpTaskStatus hpPenaltyStatus) {
        return new AppearanceResDto(appearance.getId(), appearance.getSourceType(), appearance.getRequestId(),
                appearance.getRecruitId(), appearance.getApplicationId(), title, appearance.getHostId(),
                appearance.getGuestId(), appearance.getFee(), appearance.getScheduledAt(), appearance.getStatus(),
                appearance.getEstablishedAt(), appearance.getCanceledAt(), cancelRequests, hpReleaseStatus,
                appearance.getPenaltyAmount(), appearance.getHostCancelReason(), hpPenaltyStatus, appearance.getVersion(),
                appearance.getCreatedAt(), appearance.getUpdatedAt());
    }
}
