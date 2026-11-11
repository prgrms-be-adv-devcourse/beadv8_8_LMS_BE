package com.hapbang.coupon.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 복구 재구축의 입력: DB에 확정된 발급 요청 결과 한 건({@code coupon_issue_request}에서 읽는다).
 *
 * @param acceptedAt 최초 접수 시각. 재구축하는 접수 기록의 접수 시각·만료 시각 계산에 쓴다
 * @param epoch      요청이 처리된 epoch({@code coupon_issue_request.epoch}). 재구축하는 접수 기록의 접수 epoch다.
 *                   읽어 오지 않았으면 {@code null}이고, 그때 접수 기록은 epoch 필드 없이(옛 형식) 쓴다
 */
public record RecoveryIssueResult(String issueRequestId, long memberId, CouponIssueResult result, Instant acceptedAt,
                                  Long epoch) {

    public RecoveryIssueResult {
        Objects.requireNonNull(issueRequestId, "issueRequestId");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
    }

    /** epoch를 읽어 오지 않은 결과 */
    public RecoveryIssueResult(String issueRequestId, long memberId, CouponIssueResult result, Instant acceptedAt) {
        this(issueRequestId, memberId, result, acceptedAt, null);
    }
}
