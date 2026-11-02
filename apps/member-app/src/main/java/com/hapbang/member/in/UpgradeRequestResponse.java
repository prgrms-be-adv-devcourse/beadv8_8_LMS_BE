package com.hapbang.member.in;

import java.time.LocalDateTime;

import com.hapbang.member.domain.InfluencerUpgradeRequest;
import com.hapbang.member.domain.UpgradeRequestStatus;

/** 내 승급 신청 (본인만 본다). 처리한 관리자 ID는 넣지 않는다 (내 채널 목록과 같은 공개 범위) */
public record UpgradeRequestResponse(Long id, UpgradeRequestStatus status, String requestReason,
                                     String rejectionReason, LocalDateTime requestedAt, LocalDateTime reviewedAt) {

    public static UpgradeRequestResponse from(InfluencerUpgradeRequest request) {
        return new UpgradeRequestResponse(request.getId(), request.getStatus(), request.getRequestReason(),
                request.getRejectionReason(), request.getRequestedAt(), request.getReviewedAt());
    }
}
