package com.hapbang.coupon.app;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.domain.CouponDltLog;
import com.hapbang.coupon.domain.CouponDltStatus;
import com.hapbang.coupon.out.CouponDltLogRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 제한 재시도 뒤 DLT로 보내는 발급 요청의 격리 기록(PENDING)을 남긴다. Kafka 오류 처리기가 DLT 전송 <b>전에</b> 부른다: 기록이 실패하면
 * 예외가 나가 offset을 커밋하지 않고 다시 시도하고, 기록 뒤 DLT 전송이 실패해 다시 오면 같은 기록을 두 번 남기지 않는다.
 * <ul>
 *     <li>같은 메시지 위치(토픽·partition·offset)는 한 번만 남긴다.</li>
 *     <li>같은 발급 요청의 PENDING 기록이 이미 있으면 새로 남기지 않는다(같은 요청이 두 번 실패해도 관리자 판단은 하나). 관리자 재발행은
 *     전송 전에 기존 기록을 REPLAYING으로 선점해 커밋하므로({@link CouponDltReplayUseCase}), 재발행 메시지가 다시 격리되면 이 규칙에 걸리지
 *     않고 새 PENDING이 생긴다(REPLAYING은 이 확인에 걸리지 않는다). 재발행 전송이 실패해 기존 기록을 PENDING으로 되돌리는 것과 겹치면
 *     부분 유일 인덱스가 둘 중 하나만 남긴다(어느 쪽이든 그 요청의 PENDING은 하나).</li>
 * </ul>
 */
@Slf4j
@Service
public class CouponDltRecordUseCase {

    private final CouponDltLogRepository couponDltLogRepository;
    private final CouponDltMetrics metrics;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CouponDltRecordUseCase(CouponDltLogRepository couponDltLogRepository, CouponDltMetrics metrics,
                                  PlatformTransactionManager transactionManager,
                                  @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponDltLogRepository = couponDltLogRepository;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @param couponId       읽은 메시지의 쿠폰 ID(역직렬화 실패면 key에서 읽은 값이거나 null)
     * @param issueRequestId 읽은 메시지의 요청 ID. 역직렬화 실패면 null
     * @param payload        원문
     */
    public void record(CouponDltLog.Source source, Long couponId, String issueRequestId, String payload,
                       String failureCause) {
        boolean recorded;
        try {
            recorded = Boolean.TRUE.equals(transaction.execute(status -> {
                if (alreadyRecorded(source, couponId, issueRequestId)) {
                    return false;
                }
                couponDltLogRepository.saveAndFlush(
                        CouponDltLog.isolate(source, couponId, issueRequestId, payload, failureCause, clock.instant()));
                return true;
            }));
        } catch (DataIntegrityViolationException exception) {
            String constraint = UniqueIndexViolation.constraintOf(exception).orElseThrow(() -> exception);
            if (!CouponDltLogRepository.SOURCE_UNIQUE.equals(constraint)
                    && !CouponDltLogRepository.PENDING_REQUEST_UNIQUE.equals(constraint)) {
                throw exception;
            }
            recorded = false;
        }
        if (recorded) {
            metrics.recordIsolated();
            log.error("발급 요청을 DLT로 격리했다 couponId={}, issueRequestId={}, topic={}, partition={}, offset={}, cause={}",
                    couponId, issueRequestId, source.topic(), source.partition(), source.offset(), failureCause);
        } else {
            log.warn("이미 격리 기록이 있는 발급 요청이라 기록을 더하지 않는다 couponId={}, issueRequestId={}, topic={}, partition={}, "
                    + "offset={}", couponId, issueRequestId, source.topic(), source.partition(), source.offset());
        }
    }

    private boolean alreadyRecorded(CouponDltLog.Source source, Long couponId, String issueRequestId) {
        if (couponDltLogRepository.existsBySourceTopicAndSourcePartitionAndSourceOffset(source.topic(),
                source.partition(), source.offset())) {
            return true;
        }
        return couponId != null && issueRequestId != null && couponDltLogRepository
                .existsByCouponIdAndIssueRequestIdAndStatus(couponId, issueRequestId, CouponDltStatus.PENDING);
    }
}
