package com.hapbang.coupon.app.dto;

import com.hapbang.coupon.domain.IssueStatus;

/** 발급 요청 상태(ISSUED/REJECTED/PENDING/UNKNOWN/NOT_FOUND) */
public record IssueStatusView(String issueRequestId, long couponId, IssueStatus status) {
}
