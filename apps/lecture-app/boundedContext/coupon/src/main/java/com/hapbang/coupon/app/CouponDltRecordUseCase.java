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
 * 제한 재시도 뒤 DLT로 보내는 메시지(발급 요청·주문 결제·취소 완료)의 격리 기록(PENDING)을 남긴다. Kafka 오류 처리기가 DLT 전송
 * <b>전에</b> 부른다: 기록이 실패하면 예외가 나가 offset을 커밋하지 않고 다시 시도하고, 기록 뒤 DLT 전송이 실패해 다시 오면 같은 기록을
 * 두 번 남기지 않는다.
 * <ul>
 *     <li>같은 메시지 위치(토픽·partition·offset)는 한 번만 남긴다.</li>
 *     <li>같은 발급 요청의 PENDING 기록이 이미 있으면 새로 남기지 않는다(같은 요청이 두 번 실패해도 관리자 판단은 하나). 관리자 재발행은
 *     전송 전에 기존 기록을 REPLAYING으로 선점해 커밋하므로({@link CouponDltReplayUseCase}), 재발행 메시지가 다시 격리되면 이 규칙에 걸리지
 *     않고 새 PENDING이 생긴다(REPLAYING은 이 확인에 걸리지 않는다). 재발행 전송이 실패해 기존 기록을 PENDING으로 되돌리는 것과 겹치면
 *     부분 유일 인덱스가 둘 중 하나만 남긴다(어느 쪽이든 그 요청의 PENDING은 하나).</li>
 *     <li>주문 결제·취소 완료({@link #recordOrderEvent})는 메시지 위치로만 가린다. 관리자 재처리는 Kafka로 다시 보내지 않으므로 새 격리를
 *     만들지 않는다. 강의의 재발행으로 같은 이벤트가 다른 위치에서 다시 격리되면 기록이 둘이 되지만, 재처리는 소비 멱등이 흡수한다.</li>
 * </ul>
 */
@Slf4j
@Service
public class CouponDltRecordUseCase {

    /** alert 표식: 발급 요청을 DLT로 격리했다(관리자가 재발행·영구 거절로 처리해야 한다) */
    static final String ISOLATED_LOG_TAG = "[COUPON_ISSUE_DLT_ISOLATED]";

    /** alert 표식: 주문 결제·취소 완료 이벤트를 DLT로 격리했다(관리자가 재처리·영구 거절로 처리해야 한다) */
    static final String ORDER_ISOLATED_LOG_TAG = "[COUPON_ORDER_DLT_ISOLATED]";

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
            log.error("{} 발급 요청을 DLT로 격리했다 couponId={}, issueRequestId={}, topic={}, partition={}, offset={}, cause={}",
                    ISOLATED_LOG_TAG, couponId, issueRequestId, source.topic(), source.partition(), source.offset(),
                    failureCause);
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

    /**
     * 주문 결제·취소 완료 이벤트의 격리 기록을 남긴다. 같은 메시지 위치를 이미 기록했으면 더하지 않는다.
     *
     * @param payload 원문(읽은 메시지는 이벤트 JSON, 역직렬화 실패면 받은 바이트)
     */
    public void recordOrderEvent(CouponDltLog.Source source, String payload, String failureCause) {
        boolean recorded;
        try {
            recorded = Boolean.TRUE.equals(transaction.execute(status -> {
                if (couponDltLogRepository.existsBySourceTopicAndSourcePartitionAndSourceOffset(source.topic(),
                        source.partition(), source.offset())) {
                    return false;
                }
                couponDltLogRepository.saveAndFlush(
                        CouponDltLog.isolateOrderEvent(source, payload, failureCause, clock.instant()));
                return true;
            }));
        } catch (DataIntegrityViolationException exception) {
            String constraint = UniqueIndexViolation.constraintOf(exception).orElseThrow(() -> exception);
            if (!CouponDltLogRepository.SOURCE_UNIQUE.equals(constraint)) {
                throw exception;
            }
            recorded = false;
        }
        if (recorded) {
            metrics.recordOrderIsolated();
            log.error("{} 주문 결제·취소 완료 이벤트를 DLT로 격리했다 key={}, topic={}, partition={}, offset={}, cause={}",
                    ORDER_ISOLATED_LOG_TAG, source.key(), source.topic(), source.partition(), source.offset(),
                    failureCause);
        } else {
            log.warn("이미 격리 기록이 있는 주문 이벤트 메시지라 기록을 더하지 않는다 key={}, topic={}, partition={}, offset={}",
                    source.key(), source.topic(), source.partition(), source.offset());
        }
    }
}
