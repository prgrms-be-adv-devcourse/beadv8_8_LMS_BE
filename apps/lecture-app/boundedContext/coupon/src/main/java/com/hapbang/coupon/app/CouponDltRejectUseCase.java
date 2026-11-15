package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.app.dto.CouponDltLogView;
import com.hapbang.coupon.app.port.CouponCompensationStore;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.config.CouponDltProperties;
import com.hapbang.coupon.domain.CouponDltLog;
import com.hapbang.coupon.domain.CouponDltStatus;
import com.hapbang.coupon.domain.CouponIssueRequest;
import com.hapbang.coupon.domain.CouponIssueResult;
import com.hapbang.coupon.domain.IssueCompensation;
import com.hapbang.coupon.domain.IssueDelivery;
import com.hapbang.coupon.domain.ProcessedOperation;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.CouponDltLogRepository;
import com.hapbang.coupon.out.CouponIssueRequestRepository;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.ProcessedOperationRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * DLT 영구 거절(관리자): 격리된 발급 요청을 거절로 끝내고 Redis 수량을 되돌린다.
 * <ol>
 *     <li>DB(한 트랜잭션): 발급 결과를 REJECTED·사유로 먼저 확정하고(이미 같은 요청의 거절이 있으면 그대로 둔다) 격리 기록을 REJECTED로
 *     바꾼다. 이미 발급이 확정된 요청이면 거절하지 않는다(409).</li>
 *     <li>Redis(트랜잭션 밖): compensate.lua 한 번으로 보상 표식·epoch 검증·재고 +1·회원 기록 삭제를 원자적으로 한다.</li>
 *     <li>DB: 보상 결과를 격리 기록·발급 결과에 남긴다.</li>
 * </ol>
 * 2에서 Redis에 닿지 못하면 503이고 DB는 거절된 채다. 같은 요청을 다시 보내면 1은 그대로 두고 2부터 다시 한다. 보상 표식이 있어
 * 몇 번을 다시 보내도 재고는 한 번만 되돌린다. 이전 epoch의 요청은 표식만 남고 재고는 바뀌지 않는다.
 * <p>
 * 원문을 발급 요청으로 읽을 수 없거나(역직렬화 실패) 쿠폰이 없으면 확정할 발급 요청이 없으므로 격리 기록만 REJECTED로 닫고 보상하지
 * 않는다.
 * <p>
 * 거절할 수 있는 기록은 PENDING과, 재발행 선점 lease({@code hapbang.coupon.dlt-replay-lease})가 지난 REPLAYING(선점한 프로세스가 결과를
 * 남기지 못하고 멈춤)이다. lease 안의 REPLAYING과 REPLAYED는 409 {@code COUPON_DLT_ALREADY_RESOLVED}다. lease가 지난 REPLAYING의 메시지가
 * 실제로 전달됐어도, 1에서 발급 결과를 먼저 확정하므로(이미 발급 확정이면 409) 늦게 온 소비는 그 결과를 보고 멱등하게 끝난다.
 */
@Slf4j
@Service
public class CouponDltRejectUseCase {

    /** alert 표식: 영구 거절한 요청의 Redis 수량을 보상하지 못했다(기록 없음·Redis 실패 등) */
    static final String COMPENSATION_FAILED_LOG_TAG = "[COUPON_ISSUE_COMPENSATION_FAILED]";

    private final CouponDltLogRepository couponDltLogRepository;
    private final CouponIssueRequestRepository couponIssueRequestRepository;
    private final ProcessedOperationRepository processedOperationRepository;
    private final CouponRepository couponRepository;
    private final CouponCompensationStore compensationStore;
    private final CouponDltMetrics metrics;
    private final CouponDltProperties dltProperties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CouponDltRejectUseCase(CouponDltLogRepository couponDltLogRepository,
                                  CouponIssueRequestRepository couponIssueRequestRepository,
                                  ProcessedOperationRepository processedOperationRepository,
                                  CouponRepository couponRepository, CouponCompensationStore compensationStore,
                                  CouponDltMetrics metrics, CouponDltProperties dltProperties,
                                  PlatformTransactionManager transactionManager,
                                  @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponDltLogRepository = couponDltLogRepository;
        this.couponIssueRequestRepository = couponIssueRequestRepository;
        this.processedOperationRepository = processedOperationRepository;
        this.couponRepository = couponRepository;
        this.compensationStore = compensationStore;
        this.metrics = metrics;
        this.dltProperties = dltProperties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public CouponDltLogView reject(long adminId, long dltId, String reason) {
        Rejection rejection;
        try {
            rejection = transaction.execute(status -> confirmRejection(adminId, dltId, reason));
        } catch (DataIntegrityViolationException exception) {
            // 같은 요청을 소비자가 동시에 확정했다. 다시 요청하면 그 결과를 보고 판단한다
            throw new CouponException(CouponErrorCode.COUPON_CONCURRENT_UPDATE, null, exception);
        } catch (OptimisticLockingFailureException exception) {
            // 같은 기록을 재발행·영구 거절이 동시에 바꿨다
            throw new CouponException(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED);
        }
        if (rejection.delivery() == null) {
            log.info("확정할 발급 요청이 없는 DLT 기록을 영구 거절로 닫았다 dltId={}, adminId={}", dltId, adminId);
            return rejection.view();
        }
        IssueDelivery delivery = rejection.delivery();
        IssueCompensation compensation = compensationStore.compensate(delivery.couponId(), delivery.issueRequestId(),
                delivery.memberId(), delivery.epoch());
        CouponDltLogView view = transaction.execute(status -> recordCompensation(dltId, delivery, compensation));
        metrics.recordCompensation(compensation);
        if (compensation.isSettled()) {
            log.info("DLT 발급 요청을 영구 거절하고 수량을 보상했다 dltId={}, couponId={}, memberId={}, issueRequestId={}, "
                            + "adminId={}, compensation={}", dltId, delivery.couponId(), delivery.memberId(),
                    delivery.issueRequestId(), adminId, compensation);
        } else {
            log.error("{} DLT 발급 요청을 영구 거절했지만 Redis 수량을 보상하지 못했다. 대사로 확인이 필요하다 dltId={}, couponId={}, "
                            + "memberId={}, issueRequestId={}, adminId={}, compensation={}", COMPENSATION_FAILED_LOG_TAG,
                    dltId, delivery.couponId(),
                    delivery.memberId(), delivery.issueRequestId(), adminId, compensation);
        }
        return view;
    }

    private Rejection confirmRejection(long adminId, long dltId, String reason) {
        CouponDltLog dltLog = couponDltLogRepository.findById(dltId)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_DLT_NOT_FOUND));
        Instant now = clock.instant();
        Duration replayLease = dltProperties.dltReplayLease();
        if (dltLog.getStatus() == CouponDltStatus.REPLAYED) {
            throw new CouponException(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED,
                    "이미 재발행한 DLT 기록입니다. 다시 실패했다면 새 DLT 기록을 거절해 주세요.");
        }
        if (dltLog.getStatus() == CouponDltStatus.REPLAYING && !dltLog.canClaim(now, replayLease)) {
            throw new CouponException(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED,
                    "재발행이 진행 중인 DLT 기록입니다. 결과를 확인하거나 재발행 선점이 풀린 뒤 다시 시도해 주세요.");
        }
        Optional<IssueDelivery> delivery = readDelivery(dltLog)
                .filter(value -> couponRepository.existsById(value.couponId()));
        if (delivery.isEmpty()) {
            dltLog.reject(adminId, reason, now, replayLease);
            return new Rejection(null, CouponDltLogView.from(dltLog));
        }
        confirmRejectedResult(delivery.get(), reason, now);
        dltLog.reject(adminId, reason, now, replayLease);
        return new Rejection(delivery.get(), CouponDltLogView.from(dltLog));
    }

    /** 발급 결과를 거절로 확정한다. 같은 요청의 거절이 이미 있으면 그대로 두고, 발급이 확정됐거나 다른 내용이면 409다 */
    private void confirmRejectedResult(IssueDelivery delivery, String reason, Instant now) {
        Optional<CouponIssueRequest> existing = couponIssueRequestRepository
                .findByCouponIdAndIssueRequestId(delivery.couponId(), delivery.issueRequestId());
        if (existing.isPresent()) {
            if (existing.get().getResult() == CouponIssueResult.ISSUED) {
                throw new CouponException(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED,
                        "이미 발급이 확정된 요청이라 영구 거절할 수 없습니다. 재발행으로 닫아 주세요.");
            }
            if (!delivery.sameRequestAs(existing.get())) {
                throw new CouponException(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED,
                        "같은 요청 ID에 다른 내용의 결과가 이미 있어 영구 거절할 수 없습니다.");
            }
            return;
        }
        couponIssueRequestRepository.save(delivery.toRejected(reason, now));
        if (processedOperationRepository.findByOperationId(delivery.operationId()).isEmpty()) {
            processedOperationRepository.save(ProcessedOperation.of(delivery, CouponIssueResult.REJECTED, now));
        }
    }

    private CouponDltLogView recordCompensation(long dltId, IssueDelivery delivery, IssueCompensation compensation) {
        Instant now = clock.instant();
        CouponDltLog dltLog = couponDltLogRepository.findById(dltId).orElseThrow();
        dltLog.recordCompensation(compensation, now);
        couponIssueRequestRepository.findByCouponIdAndIssueRequestId(delivery.couponId(), delivery.issueRequestId())
                .ifPresent(result -> result.recordCompensation(compensation));
        return CouponDltLogView.from(dltLog);
    }

    /** 원문을 발급 요청으로 읽는다. 읽을 수 없거나 형식이 틀리면 빈 값 */
    private static Optional<IssueDelivery> readDelivery(CouponDltLog dltLog) {
        return CouponDltPayload.read(dltLog.getPayload()).flatMap(event -> {
            try {
                return Optional.of(new IssueDelivery(event.issueRequestId(), event.couponId(), event.memberId(),
                        event.epoch(), event.issuedAt(), event.expiresAt()));
            } catch (IllegalArgumentException | NullPointerException exception) {
                return Optional.empty();
            }
        });
    }

    /** DB 확정 결과. {@code delivery}가 없으면 보상할 발급 요청이 없다 */
    private record Rejection(IssueDelivery delivery, CouponDltLogView view) {
    }
}
