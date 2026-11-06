package com.hapbang.coupon.app.dto;

import com.hapbang.coupon.domain.IssueResponseCode;

/**
 * 발급 접수 결과. 접수(ACCEPTED)와 같은 요청 재응답(ALREADY_ACCEPTED)만 이 값으로 돌려주고, 그 밖의 결과는 오류로 응답한다.
 * 접수는 완료가 아니다. 결과는 발급 요청 상태 조회로 확인한다.
 */
public record IssueRequestResult(String issueRequestId, long couponId, IssueResponseCode result) {
}
