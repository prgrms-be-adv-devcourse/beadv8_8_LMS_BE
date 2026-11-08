package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponIssueRequest;
import com.hapbang.coupon.domain.CouponIssueResult;
import com.hapbang.coupon.domain.IssueCountRule;
import com.hapbang.coupon.domain.IssueDelivery;
import com.hapbang.coupon.domain.IssueEpochRule;
import com.hapbang.coupon.domain.IssueRejectReason;
import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.coupon.domain.ProcessedOperation;
import com.hapbang.coupon.out.CouponIssueRequestRepository;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.coupon.out.ProcessedOperationRepository;
import com.hapbang.shared.coupon.event.CouponIssueRequested;

import lombok.extern.slf4j.Slf4j;

/**
 * 발급 확정 소비: Kafka로 받은 발급 요청 한 건을 DB에 확정한다. 한 트랜잭션에서
 * (1) 멱등 확인(소비 멱등 기록·기존 결과) → (2) 쿠폰의 현재 epoch 확인 → (3) 발급 쿠폰 INSERT → (4) 발급 결과 ISSUED → (5) 소비 멱등 기록
 * → (6) 확정 발급 수 조건부 +1(총수량 미만 ∧ epoch 그대로) 순서로 한다. 조건부 +1이 0행이면 트랜잭션 전체를 되돌리고, 새 트랜잭션에서
 * 쿠폰을 다시 읽어 epoch가 바뀌었으면 epoch 불일치와 똑같이 처리하고(기존 결과가 있으면 재생, 없으면 격리) 아니면 거절(총수량 초과)로
 * 확정한다. (2)의 epoch 확인과 (6)의 확정 사이에 복구 재개가 새 epoch를 커밋해도 이전 세대의 요청은 발급되지 않는다.
 * <ul>
 *     <li>멱등: 같은 작업 ID(쿠폰·요청 ID)의 같은 내용이 다시 오면(재발행·재전달) 아무것도 바꾸지 않고 성공으로 끝낸다. 동시에 처리된
 *     같은 요청은 유일 제약({@code uk_member_coupon_issue_request}·{@code uk_coupon_issue_request_coupon_request}·
 *     {@code uk_processed_operation_operation_id}·{@code uk_member_coupon_member_coupon})에 걸리고, 새 트랜잭션에서 저장된 결과를 다시
 *     읽어 같은 요청이면 성공으로 끝낸다. 같은 요청 ID의 다른 내용은 충돌로 격리한다.</li>
 *     <li>같은 회원이 다른 요청 ID로 이미 받았으면(회원 중복 유일 제약) 이 요청을 거절(이미 발급된 회원)로 확정한다. 결과를 남기지 않으면
 *     재발행 배치가 계속 다시 보낸다.</li>
 *     <li>격리(재시도 없이 DLT): 형식 오류, 없는 쿠폰, epoch가 쿠폰과 다름({@link IssueEpochRule}. 이전 세대의 늦은 전달은 새 재고·발급
 *     수를 바꾸지 않는다), 같은 요청 ID의 다른 내용. 그 밖의 실패(DB 연결 등)는 예외로 던져 제한 재시도 뒤 DLT로 간다.</li>
 *     <li>만료 시각은 이벤트에 실린 최초 접수 값을 그대로 쓴다.</li>
 * </ul>
 * 총수량 초과·이미 발급된 회원 거절은 Redis 수량을 보상하지 않는다. Redis 재고·회원 기록이 DB와 어긋난 장애 신호라 대사가 맡는다.
 */
@Slf4j
@Service
public class CouponIssueConsumerUseCase {

    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;
    private final CouponIssueRequestRepository couponIssueRequestRepository;
    private final ProcessedOperationRepository processedOperationRepository;
    private final CouponIssueConsumerMetrics metrics;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CouponIssueConsumerUseCase(CouponRepository couponRepository,
                                      MemberCouponRepository memberCouponRepository,
                                      CouponIssueRequestRepository couponIssueRequestRepository,
                                      ProcessedOperationRepository processedOperationRepository,
                                      CouponIssueConsumerMetrics metrics,
                                      PlatformTransactionManager transactionManager,
                                      @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponRepository = couponRepository;
        this.memberCouponRepository = memberCouponRepository;
        this.couponIssueRequestRepository = couponIssueRequestRepository;
        this.processedOperationRepository = processedOperationRepository;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * 발급 요청 한 건을 확정한다. 트랜잭션 밖에서 부른다(안에서 트랜잭션을 나눈다).
     *
     * @throws CouponIssueConsumerIsolationException 재시도해도 같은 결과라 바로 격리할 요청
     */
    public Result consume(CouponIssueRequested event) {
        IssueDelivery delivery = toDelivery(event);
        Result result;
        try {
            result = transaction.execute(status -> confirm(delivery));
        } catch (NotIncrementedException exception) {
            result = transaction.execute(status -> resolveNotIncremented(delivery));
        } catch (DataIntegrityViolationException exception) {
            String constraint = UniqueIndexViolation.constraintOf(exception).orElseThrow(() -> exception);
            result = transaction.execute(status -> resolveUniqueViolation(delivery, constraint, exception));
        }
        metrics.record(result);
        return result;
    }

    private static IssueDelivery toDelivery(CouponIssueRequested event) {
        try {
            return new IssueDelivery(event.issueRequestId(), event.couponId(), event.memberId(), event.epoch(),
                    event.issuedAt(), event.expiresAt());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new CouponIssueConsumerIsolationException("발급 요청 형식이 올바르지 않다: " + exception.getMessage(),
                    exception);
        }
    }

    private Result confirm(IssueDelivery delivery) {
        Optional<Result> replayed = replayIfProcessed(delivery);
        if (replayed.isPresent()) {
            return replayed.get();
        }
        requireCurrentEpoch(delivery);
        Instant now = clock.instant();
        MemberCoupon memberCoupon = memberCouponRepository.saveAndFlush(delivery.toMemberCoupon(now));
        couponIssueRequestRepository.save(delivery.toIssued(memberCoupon.getId(), now));
        processedOperationRepository.save(ProcessedOperation.of(delivery, CouponIssueResult.ISSUED, now));
        // 앞의 INSERT를 먼저 flush한 뒤(유일 제약 확인) 마지막에 쿠폰 행을 잠근다. 0행은 총량 초과이거나 그사이 epoch가 바뀐 것이다
        if (IssueCountRule.interpret(couponRepository.incrementIssuedCount(delivery.couponId(), delivery.epoch()))
                == IssueCountRule.Outcome.OVER_QUANTITY) {
            throw new NotIncrementedException();
        }
        log.info("쿠폰 발급을 확정했다 couponId={}, memberId={}, issueRequestId={}, memberCouponId={}",
                delivery.couponId(), delivery.memberId(), delivery.issueRequestId(), memberCoupon.getId());
        return Result.ISSUED;
    }

    /** 쿠폰을 읽어 요청의 epoch가 쿠폰의 지금 epoch인지 확인한다. 없는 쿠폰이거나 epoch가 다르면 격리한다 */
    private void requireCurrentEpoch(IssueDelivery delivery) {
        Coupon coupon = couponRepository.findById(delivery.couponId())
                .orElseThrow(() -> new CouponIssueConsumerIsolationException(
                        "없는 쿠폰의 발급 요청이다 couponId=" + delivery.couponId()));
        IssueEpochRule.Verdict verdict = delivery.checkEpoch(coupon.getEpoch());
        if (verdict != IssueEpochRule.Verdict.CURRENT) {
            throw new CouponIssueConsumerIsolationException("발급 요청의 epoch가 쿠폰의 현재 epoch와 다르다 verdict=" + verdict
                    + ", requestEpoch=" + delivery.epoch() + ", couponEpoch=" + coupon.getEpoch());
        }
    }

    /**
     * 조건부 +1이 0행이라 되돌린 뒤(새 트랜잭션): 그사이 같은 요청이 확정됐으면 그 결과로 끝내고, 쿠폰의 epoch가 바뀌었으면 epoch 불일치로
     * 격리하고(새 재고·발급 수를 바꾸지 않는다), epoch가 그대로면 총수량 초과 거절로 확정한다.
     */
    private Result resolveNotIncremented(IssueDelivery delivery) {
        Optional<Result> replayed = replayIfProcessed(delivery);
        if (replayed.isPresent()) {
            return replayed.get();
        }
        requireCurrentEpoch(delivery);
        return reject(delivery, IssueRejectReason.OVER_QUANTITY);
    }

    /**
     * 이미 처리한 요청이면 그 결과로 끝낸다(아무것도 바꾸지 않는다). 소비 멱등 기록을 먼저 보고, 없으면 발급 결과를 본다(영구 거절처럼
     * 소비자 밖에서 확정된 결과). 같은 작업 ID의 다른 내용이면 격리한다.
     */
    private Optional<Result> replayIfProcessed(IssueDelivery delivery) {
        Optional<ProcessedOperation> processed = processedOperationRepository.findByOperationId(delivery.operationId());
        if (processed.isPresent()) {
            if (!processed.get().sameContent(delivery)) {
                throw conflict(delivery);
            }
            return Optional.of(duplicate(delivery));
        }
        Optional<CouponIssueRequest> existing = couponIssueRequestRepository
                .findByCouponIdAndIssueRequestId(delivery.couponId(), delivery.issueRequestId());
        if (existing.isPresent()) {
            if (!delivery.sameRequestAs(existing.get())) {
                throw conflict(delivery);
            }
            return Optional.of(duplicate(delivery));
        }
        return Optional.empty();
    }

    private Result reject(IssueDelivery delivery, IssueRejectReason reason) {
        Optional<Result> replayed = replayIfProcessed(delivery);
        if (replayed.isPresent()) {
            return replayed.get();
        }
        Instant now = clock.instant();
        couponIssueRequestRepository.save(delivery.toRejected(reason.getMessage(), now));
        processedOperationRepository.save(ProcessedOperation.of(delivery, CouponIssueResult.REJECTED, now));
        log.error("발급 요청을 거절로 확정했다. Redis 접수 기록과 DB가 어긋났는지 확인이 필요하다 couponId={}, memberId={}, "
                        + "issueRequestId={}, reason={}", delivery.couponId(), delivery.memberId(), delivery.issueRequestId(),
                reason);
        return switch (reason) {
            case OVER_QUANTITY -> Result.REJECTED_OVER_QUANTITY;
            case MEMBER_ALREADY_ISSUED -> Result.REJECTED_MEMBER_ALREADY_ISSUED;
        };
    }

    /**
     * 유일 제약 위반 뒤(새 트랜잭션): 같은 요청이 먼저 확정됐으면 그 결과로 끝내고, 같은 회원이 다른 요청으로 받았으면 이 요청을 거절로
     * 확정한다. 예상하지 못한 제약이면 그대로 던진다(재시도).
     */
    private Result resolveUniqueViolation(IssueDelivery delivery, String constraint,
                                          DataIntegrityViolationException exception) {
        Optional<Result> replayed = replayIfProcessed(delivery);
        if (replayed.isPresent()) {
            return replayed.get();
        }
        if (MemberCouponRepository.MEMBER_COUPON_UNIQUE.equals(constraint)) {
            Optional<MemberCoupon> owned = memberCouponRepository
                    .findByMemberIdAndCouponId(delivery.memberId(), delivery.couponId());
            if (owned.isPresent() && !owned.get().getIssueRequestId().equals(delivery.issueRequestId())) {
                return reject(delivery, IssueRejectReason.MEMBER_ALREADY_ISSUED);
            }
        }
        // 같은 요청의 결과가 아직 보이지 않는다(동시에 처리한 쪽이 아직 커밋 전 등). 다시 시도하면 결과를 읽는다
        throw exception;
    }

    private static Result duplicate(IssueDelivery delivery) {
        log.debug("이미 처리한 발급 요청이라 다시 반영하지 않는다 couponId={}, issueRequestId={}", delivery.couponId(),
                delivery.issueRequestId());
        return Result.DUPLICATE;
    }

    private static CouponIssueConsumerIsolationException conflict(IssueDelivery delivery) {
        return new CouponIssueConsumerIsolationException("같은 발급 요청 ID에 다른 내용이 왔다 couponId=" + delivery.couponId()
                + ", issueRequestId=" + delivery.issueRequestId() + ", content=" + delivery.contentKey());
    }

    /** 처리 결과 */
    public enum Result {
        /** 발급을 확정했다 */
        ISSUED("issued"),
        /** 이미 처리한 요청이다(아무것도 바꾸지 않았다) */
        DUPLICATE("duplicate"),
        /** 확정 발급 수가 이미 총수량이라 거절로 확정했다 */
        REJECTED_OVER_QUANTITY("rejected_over_quantity"),
        /** 같은 회원이 다른 요청으로 이미 받아 거절로 확정했다 */
        REJECTED_MEMBER_ALREADY_ISSUED("rejected_member_already_issued");

        private final String metricTag;

        Result(String metricTag) {
            this.metricTag = metricTag;
        }

        String metricTag() {
            return metricTag;
        }
    }

    /** 조건부 +1이 0행이다(총량 초과 또는 epoch 변경). 트랜잭션을 되돌리려고 던진다 */
    private static final class NotIncrementedException extends RuntimeException {

        NotIncrementedException() {
            super("확정 발급 수를 올리지 못했다(총량 초과 또는 epoch 변경)", null, false, false);
        }
    }
}
