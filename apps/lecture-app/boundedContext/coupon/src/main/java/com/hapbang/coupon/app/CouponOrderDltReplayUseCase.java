package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.app.dto.CouponDltLogView;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.config.CouponDltProperties;
import com.hapbang.coupon.domain.CouponDltKind;
import com.hapbang.coupon.domain.CouponDltLog;
import com.hapbang.coupon.domain.CouponDltStatus;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.CouponDltLogRepository;
import com.hapbang.shared.lecture.event.OrderItemCancelCompletedEvent;
import com.hapbang.shared.lecture.event.OrderItemPaidEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * 주문 이벤트 DLT 재처리(관리자): 격리된 주문 결제·취소 완료 이벤트를 저장한 원문으로 소비 UseCase({@link CouponOrderConsumerUseCase})에
 * 다시 넣는다. 강의 토픽에 다시 보내지 않는다(같은 토픽의 다른 소비 그룹이 한 번 더 받지 않게).
 * <ol>
 *     <li>트랜잭션에서 PENDING(또는 lease가 지난 REPLAYING) → REPLAYING으로 선점해 커밋한다({@code version} 낙관적 락). 동시에 같은 기록을
 *     재처리·영구 거절하면 늦은 쪽이 409 {@code COUPON_DLT_ALREADY_RESOLVED}다.</li>
 *     <li>트랜잭션 밖에서 소비 UseCase를 실행한다(안에서 트랜잭션을 나눈다). 이미 반영된 이벤트면 소비 멱등이 아무것도 바꾸지 않는다.</li>
 *     <li>성공하면 REPLAYING → REPLAYED로 끝낸다. 실패하면 PENDING으로 되돌리고, 다시 해도 같은 결과인 실패(격리 사유)는 409
 *     {@code INVALID_COUPON_STATUS}(영구 거절로 닫는다), 그 밖의 실패는 그대로 던진다(다시 요청하면 된다).</li>
 * </ol>
 * 실행과 결과 기록 사이에 프로세스가 죽으면 기록은 REPLAYING으로 남고, 선점 lease({@code hapbang.coupon.dlt-replay-lease})가 지나면 다시
 * 재처리할 수 있다(소비 멱등이 겹친 실행을 흡수한다). 원문을 주문 이벤트로 읽을 수 없으면(역직렬화 실패로 격리된 메시지) 409
 * {@code INVALID_COUPON_STATUS}다.
 */
@Slf4j
@Service
public class CouponOrderDltReplayUseCase {

    private final CouponDltLogRepository couponDltLogRepository;
    private final CouponOrderConsumerUseCase couponOrderConsumerUseCase;
    private final CouponDltProperties dltProperties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CouponOrderDltReplayUseCase(CouponDltLogRepository couponDltLogRepository,
                                       CouponOrderConsumerUseCase couponOrderConsumerUseCase,
                                       CouponDltProperties dltProperties, PlatformTransactionManager transactionManager,
                                       @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponDltLogRepository = couponDltLogRepository;
        this.couponOrderConsumerUseCase = couponOrderConsumerUseCase;
        this.dltProperties = dltProperties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public CouponDltLogView replay(long adminId, long dltId) {
        Claim claim = claim(adminId, dltId);
        CouponOrderConsumerUseCase.Result result;
        try {
            result = consume(claim.event());
        } catch (CouponOrderConsumerIsolationException exception) {
            revert(dltId, claim.version());
            log.warn("DLT 주문 이벤트를 다시 처리해도 같은 이유로 실패했다 dltId={}, adminId={}, cause={}", dltId, adminId,
                    exception.getMessage());
            throw new CouponException(CouponErrorCode.INVALID_COUPON_STATUS,
                    "다시 처리해도 같은 이유로 실패합니다. 원인을 확인하고 영구 거절로 닫아 주세요: " + exception.getMessage());
        } catch (RuntimeException exception) {
            revert(dltId, claim.version());
            log.warn("DLT 주문 이벤트를 다시 처리하지 못해 PENDING으로 되돌렸다 dltId={}, adminId={}", dltId, adminId,
                    exception);
            throw exception;
        }
        CouponDltLogView view = complete(dltId, claim.version()).orElse(claim.view());
        log.info("DLT 주문 이벤트를 원문으로 다시 처리했다 dltId={}, adminId={}, result={}, status={}", dltId, adminId, result,
                view.status());
        return view;
    }

    private CouponOrderConsumerUseCase.Result consume(Object event) {
        return switch (event) {
            case OrderItemPaidEvent paid -> couponOrderConsumerUseCase.consume(paid);
            case OrderItemCancelCompletedEvent canceled -> couponOrderConsumerUseCase.consume(canceled);
            default -> throw new IllegalStateException("주문 이벤트가 아닌 원문입니다: " + event.getClass().getName());
        };
    }

    /** 실행 전 선점: PENDING(또는 lease가 지난 REPLAYING) → REPLAYING을 커밋한다 */
    private Claim claim(long adminId, long dltId) {
        try {
            return transaction.execute(status -> {
                Instant now = clock.instant();
                CouponDltLog dltLog = couponDltLogRepository.findById(dltId)
                        .filter(found -> found.getKind() == CouponDltKind.ORDER)
                        .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_DLT_NOT_FOUND));
                if (!dltLog.canClaim(now, dltProperties.dltReplayLease())) {
                    throw dltLog.getStatus() == CouponDltStatus.REPLAYING
                            ? new CouponException(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED,
                                    "다른 재처리가 진행 중입니다. 결과를 확인하거나 재처리 선점이 풀린 뒤 다시 시도해 주세요.")
                            : new CouponException(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED);
                }
                Object event = CouponDltPayload.readOrderEvent(dltLog.getPayload())
                        .orElseThrow(() -> new CouponException(CouponErrorCode.INVALID_COUPON_STATUS,
                                "원문을 주문 이벤트로 읽을 수 없어 재처리할 수 없습니다. 영구 거절로 닫아 주세요."));
                dltLog.claimReplay(adminId, now, dltProperties.dltReplayLease());
                CouponDltLog claimed = couponDltLogRepository.saveAndFlush(dltLog);
                return new Claim(event, claimed.getVersion(), CouponDltLogView.from(claimed));
            });
        } catch (OptimisticLockingFailureException exception) {
            throw new CouponException(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED);
        }
    }

    /**
     * 실행 뒤 REPLAYING → REPLAYED. 선점한 그대로일 때만(version 같음). 이미 반영했으므로 기록하지 못해도 실패로 돌려주지 않는다(기록은
     * REPLAYING으로 남고 lease가 지나면 다시 처리할 수 있다).
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
            log.warn("DLT 주문 이벤트를 다시 처리했지만 REPLAYED로 남기지 못해 선점 lease가 지나면 다시 처리할 수 있다 dltId={}",
                    dltId, exception);
            return Optional.empty();
        }
    }

    /** 실행 실패 뒤 PENDING으로 되돌린다. 선점한 그대로일 때만(version 같음). 되돌리지 못하면 lease가 지난 뒤 다시 처리할 수 있다 */
    private void revert(long dltId, long claimedVersion) {
        try {
            transaction.executeWithoutResult(status -> {
                CouponDltLog dltLog = findClaimed(dltId, claimedVersion);
                if (dltLog != null) {
                    dltLog.revertReplay(clock.instant());
                    couponDltLogRepository.saveAndFlush(dltLog);
                }
            });
        } catch (DataAccessException exception) {
            log.warn("DLT 주문 이벤트 재처리 실패 뒤 PENDING으로 되돌리지 못해 선점 lease가 지나면 다시 처리할 수 있다 dltId={}", dltId,
                    exception);
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

    /** 선점 결과: 다시 넣을 이벤트, 선점 커밋 뒤 version, 응답 */
    private record Claim(Object event, long version, CouponDltLogView view) {
    }
}
