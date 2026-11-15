package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.app.dto.CouponDltLogView;
import com.hapbang.coupon.app.port.CouponIssueEventPublisher;
import com.hapbang.coupon.app.port.CouponIssueEventPublisher.PublishResult;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.config.CouponDltProperties;
import com.hapbang.coupon.config.CouponIssueDeliveryProperties;
import com.hapbang.coupon.domain.CouponDltLog;
import com.hapbang.coupon.domain.CouponDltStatus;
import com.hapbang.coupon.domain.RecoveryStep;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.CouponDltLogRepository;
import com.hapbang.coupon.out.RecoveryRunRepository;
import com.hapbang.shared.coupon.event.CouponIssueRequested;

import lombok.extern.slf4j.Slf4j;

/**
 * DLT 재발행(관리자): 격리된 발급 요청을 원문 그대로(같은 요청 ID·같은 epoch) 원 토픽에 다시 보낸다.
 * 재발행은 처리 완료가 아니다. 결과는 발급 요청의 DB 결과로 확인하고, 다시 실패하면 새 PENDING 기록이 생긴다.
 * <ol>
 *     <li>전송 <b>전에</b> 트랜잭션에서 PENDING → REPLAYING(선점 시각·관리자)으로 바꿔 커밋한다(선점, {@code version} 낙관적 락). 동시에
 *     같은 기록을 재발행·영구 거절하면 늦은 쪽이 409 {@code COUPON_DLT_ALREADY_RESOLVED}다. 선점이 먼저 커밋되므로 재발행 메시지가 바로 다시
 *     격리돼도 {@code CouponDltRecordUseCase}는 "같은 요청의 PENDING이 이미 있으면 생략" 규칙에 걸리지 않고 새 PENDING을 남긴다.</li>
 *     <li>트랜잭션 밖에서 전송한다.</li>
 *     <li>broker ACK를 받으면 REPLAYING → REPLAYED로 끝낸다.</li>
 *     <li>ACK를 받지 못하면 503이고, 기록을 PENDING으로 되돌린다(다시 요청하면 된다. 늦게 도착한 메시지가 있어도 소비 멱등이 흡수한다).
 *     단 그사이 같은 요청의 새 PENDING 격리가 생겼으면(시간 초과였지만 실제로는 전달돼 다시 격리됨) 되돌리지 않고 이 기록을 REPLAYED로
 *     끝낸다. 같은 요청의 PENDING은 하나이고, 그 새 기록이 관리자 판단을 기다린다(이 기록까지 미처리로 남기면 같은 요청을 두 번 판단하게
 *     된다).</li>
 * </ol>
 * 선점과 결과 기록 사이에 프로세스가 죽으면 기록은 REPLAYING으로 남는다. 선점 lease({@code hapbang.coupon.dlt-replay-lease})가 지나면 다시
 * 재발행하거나 영구 거절할 수 있고, 그동안에는 409다. 이미 보냈던 메시지와 겹쳐도 소비 멱등이 흡수한다. 선점 lease가 지나 다른 요청이 다시
 * 선점했으면(version이 바뀜) 먼저 선점한 쪽은 결과를 남기지 않는다.
 * <p>
 * PENDING·lease가 지난 REPLAYING이 아니면 409 {@code COUPON_DLT_ALREADY_RESOLVED}, 원문을 읽을 수 없으면(역직렬화 실패로 격리된 메시지)
 * 409, 그 쿠폰의 Redis 유실 복구가 끝나지 않았으면 409 {@code COUPON_RECOVERY_IN_PROGRESS}다(복구가 확정한 Kafka 경계 밖으로 전송하지 않게
 * 한다).
 */
@Slf4j
@Service
public class CouponDltReplayUseCase {

    private final CouponDltLogRepository couponDltLogRepository;
    private final RecoveryRunRepository recoveryRunRepository;
    private final CouponIssueEventPublisher publisher;
    private final CouponIssueDeliveryProperties properties;
    private final CouponDltProperties dltProperties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CouponDltReplayUseCase(CouponDltLogRepository couponDltLogRepository,
                                  RecoveryRunRepository recoveryRunRepository, CouponIssueEventPublisher publisher,
                                  CouponIssueDeliveryProperties properties, CouponDltProperties dltProperties,
                                  PlatformTransactionManager transactionManager,
                                  @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponDltLogRepository = couponDltLogRepository;
        this.recoveryRunRepository = recoveryRunRepository;
        this.publisher = publisher;
        this.properties = properties;
        this.dltProperties = dltProperties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public CouponDltLogView replay(long adminId, long dltId) {
        Claim claim = claim(adminId, dltId);
        CouponIssueRequested event = claim.event();
        PublishResult result = publisher.publish(event, properties.republish().ackTimeout());
        if (!result.acked()) {
            Settlement settlement = settleFailure(dltId, claim.version(), event);
            log.warn("DLT 발급 요청을 다시 보내지 못했다 dltId={}, couponId={}, issueRequestId={}, adminId={}, result={}, "
                    + "settlement={}", dltId, event.couponId(), event.issueRequestId(), adminId, result, settlement);
            throw new CouponException(CouponErrorCode.COUPON_EVENT_PUBLISH_FAILED, switch (settlement) {
                case REVERTED -> "발급 요청을 다시 보내지 못했습니다. 잠시 후 다시 시도해 주세요.";
                case SUPERSEDED -> "발급 요청을 다시 보내지 못했지만 같은 요청이 다시 격리되었습니다. 새 DLT 기록을 확인해 주세요.";
                case LEFT_CLAIMED -> "발급 요청을 다시 보내지 못했습니다. 재발행 선점이 풀린 뒤 다시 시도해 주세요.";
            });
        }
        CouponDltLogView view = complete(dltId, claim.version()).orElse(claim.view());
        log.info("DLT 발급 요청을 원 토픽에 다시 보냈다 dltId={}, couponId={}, issueRequestId={}, adminId={}, status={}", dltId,
                event.couponId(), event.issueRequestId(), adminId, view.status());
        return view;
    }

    /** 전송 전 선점: PENDING(또는 lease가 지난 REPLAYING) → REPLAYING을 커밋한다 */
    private Claim claim(long adminId, long dltId) {
        try {
            return transaction.execute(status -> {
                Instant now = clock.instant();
                CouponDltLog dltLog = findClaimable(dltId, now);
                CouponIssueRequested event = CouponDltPayload.read(dltLog.getPayload())
                        .orElseThrow(() -> new CouponException(CouponErrorCode.INVALID_COUPON_STATUS,
                                "원문을 발급 요청으로 읽을 수 없어 재발행할 수 없습니다. 영구 거절로 닫아 주세요."));
                if (recoveryRunRepository.findFirstByCouponIdAndStepNotIn(event.couponId(), RecoveryStep.finished())
                        .isPresent()) {
                    throw new CouponException(CouponErrorCode.COUPON_RECOVERY_IN_PROGRESS,
                            "이 쿠폰의 Redis 유실 복구가 끝나지 않아 재발행할 수 없습니다. 복구가 끝난 뒤 다시 시도해 주세요.");
                }
                dltLog.claimReplay(adminId, now, dltProperties.dltReplayLease());
                CouponDltLog claimed = couponDltLogRepository.saveAndFlush(dltLog);
                return new Claim(event, claimed.getVersion(), CouponDltLogView.from(claimed));
            });
        } catch (OptimisticLockingFailureException exception) {
            throw new CouponException(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED);
        }
    }

    /**
     * ACK 뒤 REPLAYING → REPLAYED. 선점한 그대로일 때만(version 같음). 메시지는 이미 보냈으므로 기록하지 못해도 실패로 돌려주지 않는다
     * (기록은 REPLAYING으로 남고 lease가 지나면 다시 처리할 수 있다).
     *
     * @return 끝낸 기록. 다른 요청이 다시 선점했거나 기록하지 못했으면 빈 값
     */
    private Optional<CouponDltLogView> complete(long dltId, long claimedVersion) {
        try {
            return Optional.ofNullable(transaction.execute(status -> {
                CouponDltLog dltLog = findClaimed(dltId, claimedVersion);
                if (dltLog == null) {
                    return null;
                }
                dltLog.completeReplay(clock.instant());
                return CouponDltLogView.from(couponDltLogRepository.saveAndFlush(dltLog));
            }));
        } catch (DataAccessException exception) {
            log.warn("DLT 재발행을 보냈지만 REPLAYED로 남기지 못해 선점 lease가 지나면 다시 처리할 수 있다 dltId={}", dltId,
                    exception);
            return Optional.empty();
        }
    }

    /**
     * 전송 실패 뒤 선점을 정리한다. 선점한 그대로일 때만(version 같음): 같은 요청의 새 PENDING이 없으면 PENDING으로 되돌리고, 있으면
     * REPLAYED로 끝낸다.
     */
    private Settlement settleFailure(long dltId, long claimedVersion, CouponIssueRequested event) {
        try {
            return transaction.execute(status -> {
                CouponDltLog dltLog = findClaimed(dltId, claimedVersion);
                if (dltLog == null) {
                    return Settlement.LEFT_CLAIMED;
                }
                if (couponDltLogRepository.existsPendingFor(event.couponId(), event.issueRequestId())) {
                    dltLog.completeReplay(clock.instant());
                    couponDltLogRepository.saveAndFlush(dltLog);
                    return Settlement.SUPERSEDED;
                }
                dltLog.revertReplay(clock.instant());
                couponDltLogRepository.saveAndFlush(dltLog);
                return Settlement.REVERTED;
            });
        } catch (DataIntegrityViolationException exception) {
            // 확인과 저장 사이에 같은 요청의 새 PENDING이 커밋됐다(부분 유일 인덱스). 그 기록이 남으므로 이 기록은 끝낸다
            String constraint = UniqueIndexViolation.constraintOf(exception).orElseThrow(() -> exception);
            if (!CouponDltLogRepository.PENDING_REQUEST_UNIQUE.equals(constraint)) {
                throw exception;
            }
            return supersede(dltId, claimedVersion);
        } catch (OptimisticLockingFailureException exception) {
            return Settlement.LEFT_CLAIMED;
        }
    }

    private Settlement supersede(long dltId, long claimedVersion) {
        try {
            return transaction.execute(status -> {
                CouponDltLog dltLog = findClaimed(dltId, claimedVersion);
                if (dltLog == null) {
                    return Settlement.LEFT_CLAIMED;
                }
                dltLog.completeReplay(clock.instant());
                couponDltLogRepository.saveAndFlush(dltLog);
                return Settlement.SUPERSEDED;
            });
        } catch (OptimisticLockingFailureException exception) {
            return Settlement.LEFT_CLAIMED;
        }
    }

    /** 선점한 그대로(REPLAYING·version 같음)인 기록. 아니면 null */
    private CouponDltLog findClaimed(long dltId, long claimedVersion) {
        CouponDltLog dltLog = couponDltLogRepository.findById(dltId).orElse(null);
        if (dltLog == null || dltLog.getVersion() != claimedVersion
                || dltLog.getStatus() != CouponDltStatus.REPLAYING) {
            return null;
        }
        return dltLog;
    }

    private CouponDltLog findClaimable(long dltId, Instant now) {
        CouponDltLog dltLog = couponDltLogRepository.findById(dltId)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_DLT_NOT_FOUND));
        if (!dltLog.canClaim(now, dltProperties.dltReplayLease())) {
            if (dltLog.getStatus() == CouponDltStatus.REPLAYING) {
                throw new CouponException(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED,
                        "다른 재발행이 진행 중입니다. 결과를 확인하거나 재발행 선점이 풀린 뒤 다시 시도해 주세요.");
            }
            throw new CouponException(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED);
        }
        return dltLog;
    }

    /** 선점 결과: 보낼 원문, 선점 커밋 뒤 version, 응답 */
    private record Claim(CouponIssueRequested event, long version, CouponDltLogView view) {
    }

    /** 전송 실패 뒤 정리 결과 */
    private enum Settlement {
        /** PENDING으로 되돌렸다 */
        REVERTED,
        /** 같은 요청이 다시 격리돼 이 기록은 REPLAYED로 끝냈다 */
        SUPERSEDED,
        /** 다른 요청이 다시 선점했거나 바꾸지 못해 그대로 둔다 */
        LEFT_CLAIMED
    }
}
