package com.hapbang.coupon.out;

import com.hapbang.coupon.domain.CouponIssueResult;

/** 대사용 발급 요청 결과 한 건 */
public record IssueResultRow(String issueRequestId, Long memberId, CouponIssueResult result, long epoch) {
}
