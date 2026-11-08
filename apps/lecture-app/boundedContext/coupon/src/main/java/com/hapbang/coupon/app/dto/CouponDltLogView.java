package com.hapbang.coupon.app.dto;

import java.time.Instant;

import com.hapbang.coupon.domain.CouponDltLog;
import com.hapbang.coupon.domain.CouponDltStatus;
import com.hapbang.coupon.domain.IssueCompensation;

/**
 * DLT 격리 기록(관리자 조회).
 *
 * @param couponId           쿠폰 ID. 역직렬화에 실패한 메시지는 key에서 읽은 값이거나 없다
 * @param issueRequestId     발급 요청 ID. 역직렬화에 실패한 메시지는 없다
 * @param payload            원문
 * @param status             PENDING·REPLAYING(재발행 선점 중)·REPLAYED(재발행은 처리 완료가 아니다)·REJECTED
 * @param replayClaimedAt    재발행 선점 시각. 선점 lease를 이 시각부터 잰다. 선점한 적 없거나 되돌렸으면 없다
 * @param compensationResult 영구 거절 뒤 Redis 수량 보상 결과. 보상 전·보상할 수 없는 기록은 없다
 */
public record CouponDltLogView(Long dltId, Long couponId, String issueRequestId, String sourceTopic, int sourcePartition,
                               long sourceOffset, String payload, String failureCause, CouponDltStatus status,
                               Instant replayClaimedAt, String rejectReason, Long handledBy, Instant handledAt,
                               IssueCompensation compensationResult, Instant createdAt) {

    public static CouponDltLogView from(CouponDltLog log) {
        return new CouponDltLogView(log.getId(), log.getCouponId(), log.getIssueRequestId(), log.getSourceTopic(),
                log.getSourcePartition(), log.getSourceOffset(), log.getPayload(), log.getFailureCause(),
                log.getStatus(), log.getReplayClaimedAt(), log.getRejectReason(), log.getHandledBy(), log.getHandledAt(),
                log.getCompensationResult(), log.getCreatedAt());
    }
}
