package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Qualifier;
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

import lombok.extern.slf4j.Slf4j;

/**
 * 주문 이벤트 DLT 영구 거절(관리자): 격리된 주문 결제·취소 완료 이벤트를 반영하지 않기로 하고 격리 기록만 REJECTED(사유)로 닫는다. 쿠폰
 * 사용 상태·소비 멱등 기록·Redis는 바꾸지 않는다(사용 경로는 Redis를 쓰지 않고, 보상할 수량도 없다).
 * <p>
 * 거절할 수 있는 기록은 PENDING과 재처리 선점 lease가 지난 REPLAYING이다. 이미 다시 처리한 기록(REPLAYED)과 lease 안의 REPLAYING은 409
 * {@code COUPON_DLT_ALREADY_RESOLVED}다. 이미 거절한 기록을 다시 거절하면 그대로 돌려준다.
 * <p>
 * 원문의 이벤트가 이미 같은 내용으로 반영됐으면(소비 멱등 기록이 있음) 거절하지 않고 409 {@code COUPON_DLT_ALREADY_RESOLVED}다. 재처리가
 * 소비를 커밋한 뒤 REPLAYED를 남기기 전에 멈춰 REPLAYING으로 남은 기록이나, 강의의 재발행이 따로 반영된 기록이 그렇다. 재처리로 닫으면
 * 소비 멱등이 아무것도 바꾸지 않고 REPLAYED로 끝난다.
 */
@Slf4j
@Service
public class CouponOrderDltRejectUseCase {

    private final CouponDltLogRepository couponDltLogRepository;
    private final CouponOrderConsumerUseCase couponOrderConsumerUseCase;
    private final CouponDltProperties dltProperties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CouponOrderDltRejectUseCase(CouponDltLogRepository couponDltLogRepository,
                                       CouponOrderConsumerUseCase couponOrderConsumerUseCase,
                                       CouponDltProperties dltProperties, PlatformTransactionManager transactionManager,
                                       @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponDltLogRepository = couponDltLogRepository;
        this.couponOrderConsumerUseCase = couponOrderConsumerUseCase;
        this.dltProperties = dltProperties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public CouponDltLogView reject(long adminId, long dltId, String reason) {
        CouponDltLogView view;
        try {
            view = transaction.execute(status -> {
                CouponDltLog dltLog = couponDltLogRepository.findById(dltId)
                        .filter(found -> found.getKind() == CouponDltKind.ORDER)
                        .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_DLT_NOT_FOUND));
                Instant now = clock.instant();
                Duration replayLease = dltProperties.dltReplayLease();
                if (dltLog.getStatus() == CouponDltStatus.REPLAYED) {
                    throw new CouponException(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED,
                            "이미 다시 처리한 DLT 기록입니다.");
                }
                if (dltLog.getStatus() == CouponDltStatus.REPLAYING && !dltLog.canClaim(now, replayLease)) {
                    throw new CouponException(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED,
                            "재처리가 진행 중인 DLT 기록입니다. 결과를 확인하거나 재처리 선점이 풀린 뒤 다시 시도해 주세요.");
                }
                // 이미 거절한 기록은 다시 거절해도 바뀌지 않으므로 반영 여부를 보지 않는다
                if (dltLog.getStatus() != CouponDltStatus.REJECTED
                        && CouponDltPayload.readOrderEvent(dltLog.getPayload())
                                .filter(couponOrderConsumerUseCase::isProcessed).isPresent()) {
                    throw new CouponException(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED,
                            "이미 반영된 이벤트입니다. 재처리로 닫아 주세요.");
                }
                dltLog.reject(adminId, reason, now, replayLease);
                return CouponDltLogView.from(couponDltLogRepository.saveAndFlush(dltLog));
            });
        } catch (OptimisticLockingFailureException exception) {
            // 같은 기록을 재처리·영구 거절이 동시에 바꿨다
            throw new CouponException(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED);
        }
        log.info("DLT 주문 이벤트를 영구 거절로 닫았다 dltId={}, adminId={}, topic={}, partition={}, offset={}", dltId, adminId,
                view.sourceTopic(), view.sourcePartition(), view.sourceOffset());
        return view;
    }
}
