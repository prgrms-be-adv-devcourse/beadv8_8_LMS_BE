package com.hapbang.coupon.app.dto;

import java.time.Instant;

import com.hapbang.coupon.domain.IssueResponseCode;

/**
 * 발급 접수 결과. 접수(ACCEPTED)와 같은 요청 재응답(ALREADY_ACCEPTED)만 이 값으로 돌려주고, 그 밖의 결과는 오류로 응답한다.
 * 접수는 완료가 아니다. 결과는 발급 요청 상태 조회로 확인한다.
 *
 * @param expiresAt 발급될 쿠폰의 만료 시각(밀리초). 재응답이면 최초 접수 때 정한 값이다
 */
public record IssueRequestResult(String issueRequestId, long couponId, IssueResponseCode result, Instant expiresAt) {
}
