package com.hapbang.coupon.app.dto;

import com.hapbang.coupon.domain.IssueStatus;

/**
 * 발급 요청 상태(ISSUED/REJECTED/PENDING/UNKNOWN/NOT_FOUND).
 *
 * @param rejectReason 거절 사유. REJECTED일 때만 있고(확정 결과에 사유가 없으면 그때도 {@code null}), 그 밖에는 {@code null}이다
 */
public record IssueStatusView(String issueRequestId, long couponId, IssueStatus status, String rejectReason) {
}
