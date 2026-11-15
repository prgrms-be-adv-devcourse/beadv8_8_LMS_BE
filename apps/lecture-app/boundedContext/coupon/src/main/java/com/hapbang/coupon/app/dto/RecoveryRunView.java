package com.hapbang.coupon.app.dto;

import java.time.Instant;

import com.hapbang.coupon.domain.RecoveryRun;
import com.hapbang.coupon.domain.RecoveryStep;

/**
 * 복구 실행 응답.
 *
 * @param requestsLost     Redis 접수 기록 유실이 의심된다. 그 사이 접수는 재발행할 수 없어 결과가 UNKNOWN으로 남을 수 있다
 * @param kafkaBoundary    발급 요청 토픽의 partition별 끝 offset({@code partition:offset,...})
 * @param dltBoundary      DLT 토픽의 partition별 끝 offset
 * @param unresolvedCount  마지막 결과 확인의 미처리 합계. 0이어야 재구축할 수 있다
 * @param unresolvedDetail 미처리 내역(재발행 대기·Kafka lag·DLT PENDING)
 * @param abortedBy        복구를 취소한 관리자. 취소하지 않았으면 null
 * @param blockedAt        신규 접수·재발행 배치가 멈춘 시각. 경계 확정은 여기서 생산자 전송 최대 시간 + 여유가 지난 뒤에 한다
 * @param abortReason      예외 취소의 사유(재개 결과를 모르는 채 Redis 유실이 확인됨). 재구축 전 취소이거나 취소하지 않았으면 null
 */
public record RecoveryRunView(long recoveryId, long couponId, long previousEpoch, long newEpoch, RecoveryStep step,
                              boolean requestsLost, Instant republishedAt, Integer republishedCount,
                              String kafkaBoundary, String dltBoundary, Long unresolvedCount, String unresolvedDetail,
                              String resumeBasis, String resumeEvidence, long startedBy, Instant createdAt,
                              Instant updatedAt, Instant resumedAt, Long abortedBy, Instant abortedAt,
                              Instant blockedAt, String abortReason) {

    public static RecoveryRunView from(RecoveryRun run) {
        return new RecoveryRunView(run.getId(), run.getCouponId(), run.getPreviousEpoch(), run.getNewEpoch(),
                run.getStep(), run.isRequestsLost(), run.getRepublishedAt(), run.getRepublishedCount(),
                run.getKafkaBoundary(), run.getDltBoundary(), run.getUnresolvedCount(), run.getUnresolvedDetail(),
                run.getResumeBasis(), run.getResumeEvidence(), run.getStartedBy(), run.getCreatedAt(),
                run.getUpdatedAt(), run.getResumedAt(), run.getAbortedBy(), run.getAbortedAt(), run.getBlockedAt(),
                run.getAbortReason());
    }
}
