package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.domain.CouponUsage;
import com.hapbang.coupon.domain.ProcessedOperation;
import com.hapbang.coupon.domain.UsageEventOutcome;
import com.hapbang.coupon.domain.usage.UsageContentKey;
import com.hapbang.coupon.out.CouponUsageRepository;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.coupon.out.MemberCouponState;
import com.hapbang.coupon.out.ProcessedOperationRepository;
import com.hapbang.shared.lecture.event.OrderItemCancelCompletedEvent;
import com.hapbang.shared.lecture.event.OrderItemPaidEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * 주문 결제·취소 완료 소비: 강의가 보낸 주문 항목의 결제 완료·취소(반환) 완료를 그 항목의 쿠폰 사용 시도와 발급 쿠폰에 반영한다.
 * 한 트랜잭션에서 (1) 주문 항목의 사용 시도를 행 잠금으로 읽기 → (2) 소비 멱등 확인(작업 ID = eventId) → (3) 이벤트의 사용 시도(발급
 * 쿠폰, generation)를 찾아 회원·주문을 확인 → (4) 발급 쿠폰 ID 오름차순으로 사용 시도 전이({@link CouponUsage#applyPaid}·
 * {@link CouponUsage#applyCancelCompleted})와 발급 쿠폰 generation 조건부 갱신 → (5) 소비 멱등 기록 순서로 한다. 전이 규칙은 도메인에
 * 있고 여기서는 부르기만 한다. 행 잠금 덕분에 같은 주문 항목의 Kafka 소비와 관리자 DLT 재처리가 겹쳐도 차례로 처리되고, 뒤에 온 쪽이
 * 앞의 커밋을 덮어쓰지 않는다.
 * <ul>
 *     <li>결제 완료: 사용 시도 RESERVED → PAID, 발급 쿠폰 RESERVED → USED. 기한은 다시 보지 않는다.</li>
 *     <li>취소 완료: 사용 시도 RESERVED·PAID → CANCELED, 발급 쿠폰 RESERVED·USED → 만료 전이면 AVAILABLE, 아니면 EXPIRED.</li>
 *     <li>순서 역전·이전 generation: 취소·해제가 먼저 끝난 시도에 온 결제 완료, 해제된 시도의 취소 완료, 발급 쿠폰이 다른 generation으로
 *     넘어간 이벤트는 사실만 남기고 상태를 바꾸지 않는다.</li>
 *     <li>멱등: 같은 eventId·같은 내용이 다시 오면 아무것도 바꾸지 않고 끝낸다. 동시에 처리된 같은 이벤트는 작업 ID 유일 제약에 걸리고,
 *     새 트랜잭션에서 기록을 다시 읽어 끝낸다. 같은 eventId의 다른 내용은 격리한다.</li>
 *     <li>격리(재시도 없이 DLT): 같은 발급 쿠폰이 두 번 든 이벤트, 없는 사용 시도, 회원·주문이 사용 시도와 다름, 같은 eventId의 다른 내용.
 *     발급 쿠폰 조건부 갱신이 0행이면(그사이 바뀜) 예외로 던져 제한 재시도에서 다시 읽는다.</li>
 * </ul>
 * 사용 경로라 Redis를 쓰지 않는다.
 */
@Slf4j
@Service
public class CouponOrderConsumerUseCase {

    static final String EVENT_PAID = "paid";
    static final String EVENT_CANCEL_COMPLETED = "cancel_completed";

    private final CouponUsageRepository couponUsageRepository;
    private final MemberCouponRepository memberCouponRepository;
    private final ProcessedOperationRepository processedOperationRepository;
    private final CouponOrderConsumerMetrics metrics;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CouponOrderConsumerUseCase(CouponUsageRepository couponUsageRepository,
                                      MemberCouponRepository memberCouponRepository,
                                      ProcessedOperationRepository processedOperationRepository,
                                      CouponOrderConsumerMetrics metrics,
                                      PlatformTransactionManager transactionManager,
                                      @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponUsageRepository = couponUsageRepository;
        this.memberCouponRepository = memberCouponRepository;
        this.processedOperationRepository = processedOperationRepository;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * 결제 완료 한 건을 반영한다. 트랜잭션 밖에서 부른다(안에서 트랜잭션을 나눈다).
     *
     * @throws CouponOrderConsumerIsolationException 재시도해도 같은 결과라 바로 격리할 이벤트
     */
    public Result consume(OrderItemPaidEvent event) {
        return consume(settlementOf(event));
    }

    /**
     * 취소·반환 완료 한 건을 반영한다. 트랜잭션 밖에서 부른다(안에서 트랜잭션을 나눈다).
     *
     * @throws CouponOrderConsumerIsolationException 재시도해도 같은 결과라 바로 격리할 이벤트
     */
    public Result consume(OrderItemCancelCompletedEvent event) {
        return consume(settlementOf(event));
    }

    /**
     * 이 이벤트가 이미 같은 내용으로 반영됐는가(소비 멱등 기록이 있다). 관리자 영구 거절이 이미 반영된 이벤트의 격리 기록을 거절로 닫지
     * 않게 확인한다. 호출자의 트랜잭션 안에서 읽는다.
     *
     * @param event {@link OrderItemPaidEvent} 또는 {@link OrderItemCancelCompletedEvent}
     */
    public boolean isProcessed(Object event) {
        Settlement settlement = switch (event) {
            case OrderItemPaidEvent paid -> settlementOf(paid);
            case OrderItemCancelCompletedEvent canceled -> settlementOf(canceled);
            default -> throw new IllegalArgumentException("주문 결제·취소 완료 이벤트가 아닙니다: " + event.getClass().getName());
        };
        return processedOperationRepository.findByOperationId(settlement.eventId())
                .filter(processed -> processed.sameContent(settlement.contentKey()))
                .isPresent();
    }

    private static Settlement settlementOf(OrderItemPaidEvent event) {
        List<UsageRef> usages = event.usages().stream()
                .map(usage -> new UsageRef(usage.memberCouponId(), usage.generation()))
                .toList();
        return new Settlement(EVENT_PAID, event.eventId(), event.orderId(), event.orderItemId(), event.memberId(),
                usages, event.paidAt(), List.of());
    }

    private static Settlement settlementOf(OrderItemCancelCompletedEvent event) {
        List<UsageRef> usages = event.usages().stream()
                .map(usage -> new UsageRef(usage.memberCouponId(), usage.generation()))
                .toList();
        // 지갑 작업 ID와 예산 복구 결과도 내용에 넣는다. 같은 eventId에 다른 반환 결과가 오면 충돌로 가린다
        List<String> extraParts = new ArrayList<>();
        extraParts.add("b" + Objects.toString(event.billingReleaseRequestId(), ""));
        event.budgetRestorations().forEach(restoration -> extraParts.add("p" + restoration.couponId() + "|"
                + restoration.memberCouponId() + "|" + restoration.generation() + "|" + restoration.amount()));
        return new Settlement(EVENT_CANCEL_COMPLETED, event.eventId(), event.orderId(), event.orderItemId(),
                event.memberId(), usages, event.completedAt(), extraParts);
    }

    private Result consume(Settlement settlement) {
        requireDistinctUsages(settlement);
        Result result;
        try {
            result = transaction.execute(status -> apply(settlement));
        } catch (DataIntegrityViolationException exception) {
            if (!CouponUsageSupport.isOperationIdViolation(exception)) {
                throw exception;
            }
            // 같은 이벤트를 동시에 먼저 처리했다. 그 기록을 다시 읽어 끝낸다
            result = transaction.execute(status -> replayIfProcessed(settlement).orElseThrow(() -> exception));
        }
        metrics.record(settlement.event(), result);
        return result;
    }

    private Result apply(Settlement settlement) {
        // 주문 항목의 사용 시도를 먼저 잠근다. 같은 항목의 다른 소비(Kafka·관리자 재처리)는 이 커밋을 기다렸다가 커밋된 상태와 멱등 기록을
        // 읽는다(READ COMMITTED에서 잠금 뒤의 조회는 앞의 커밋을 본다)
        List<CouponUsage> usages = couponUsageRepository.findForUpdateByOrderItemId(settlement.orderItemId());
        Optional<Result> replayed = replayIfProcessed(settlement);
        if (replayed.isPresent()) {
            return replayed.get();
        }
        Map<UsageRef, CouponUsage> found = new HashMap<>();
        for (CouponUsage usage : usages) {
            found.put(new UsageRef(usage.getMemberCouponId(), usage.getGeneration()), usage);
        }
        Instant now = clock.instant();
        boolean applied = false;
        // 여러 장은 발급 쿠폰 ID 오름차순으로 갱신한다(교착 회피)
        for (UsageRef ref : settlement.usages().stream()
                .sorted(Comparator.comparingLong(UsageRef::memberCouponId))
                .toList()) {
            CouponUsage usage = requireUsage(settlement, found.get(ref), ref);
            applied |= applyTo(settlement, usage, now) == UsageEventOutcome.APPLIED;
        }
        Result result = applied ? Result.APPLIED : Result.FACT_ONLY;
        processedOperationRepository.saveAndFlush(ProcessedOperation.ofWork(settlement.eventId(),
                settlement.contentKey(), result.name(), null, now));
        return result;
    }

    /** 사용 시도 하나에 이벤트를 반영하고, 상태를 바꿨으면 발급 쿠폰을 같은 generation의 조건부 갱신으로 바꾼다 */
    private UsageEventOutcome applyTo(Settlement settlement, CouponUsage usage, Instant now) {
        MemberCouponState coupon = memberCouponRepository.findStateById(usage.getMemberCouponId())
                .orElseThrow(() -> new IllegalStateException("사용 시도의 발급 쿠폰이 없습니다: " + usage.getMemberCouponId()));
        boolean paid = EVENT_PAID.equals(settlement.event());
        UsageEventOutcome outcome = paid
                ? usage.applyPaid(settlement.eventId(), settlement.occurredAt(), coupon.status(), coupon.generation(), now)
                : usage.applyCancelCompleted(settlement.eventId(), settlement.occurredAt(), coupon.status(),
                        coupon.generation(), coupon.expiresAt(), now);
        if (outcome == UsageEventOutcome.APPLIED) {
            int updated = paid
                    ? memberCouponRepository.markUsed(usage.getMemberCouponId(), usage.getGeneration(), now)
                    : memberCouponRepository.restore(usage.getMemberCouponId(), usage.getGeneration(), now);
            if (updated != 1) {
                // 읽은 뒤 그사이 바뀌었다. 트랜잭션을 되돌리고 제한 재시도에서 다시 읽는다
                throw new IllegalStateException("발급 쿠폰이 읽은 뒤 바뀌어 반영하지 못했습니다 memberCouponId="
                        + usage.getMemberCouponId() + ", generation=" + usage.getGeneration());
            }
            log.info("주문 {} 이벤트를 쿠폰 사용에 반영했다 eventId={}, orderItemId={}, memberCouponId={}, generation={}, "
                            + "usageStatus={}, restoredTo={}", settlement.event(), settlement.eventId(),
                    settlement.orderItemId(), usage.getMemberCouponId(), usage.getGeneration(), usage.getStatus(),
                    usage.getRestoredTo());
        } else if (outcome == UsageEventOutcome.FACT_ONLY) {
            log.warn("주문 {} 이벤트의 사실만 남기고 쿠폰 상태는 바꾸지 않았다 eventId={}, orderItemId={}, memberCouponId={}, "
                            + "generation={}, usageStatus={}, couponStatus={}, couponGeneration={}", settlement.event(),
                    settlement.eventId(), settlement.orderItemId(), usage.getMemberCouponId(), usage.getGeneration(),
                    usage.getStatus(), coupon.status(), coupon.generation());
        }
        return outcome;
    }

    private static CouponUsage requireUsage(Settlement settlement, CouponUsage usage, UsageRef ref) {
        if (usage == null) {
            throw new CouponOrderConsumerIsolationException("주문 항목에 이벤트의 사용 시도가 없다 eventId="
                    + settlement.eventId() + ", orderItemId=" + settlement.orderItemId() + ", memberCouponId="
                    + ref.memberCouponId() + ", generation=" + ref.generation());
        }
        if (usage.getMemberId() != settlement.memberId() || !usage.belongsTo(settlement.orderId())) {
            throw new CouponOrderConsumerIsolationException("이벤트의 회원·주문이 사용 시도와 다르다 eventId="
                    + settlement.eventId() + ", memberId=" + settlement.memberId() + ", orderId=" + settlement.orderId()
                    + ", usageMemberId=" + usage.getMemberId() + ", usageOrderId=" + usage.getOrderId());
        }
        return usage;
    }

    /** 이미 처리한 이벤트면 아무것도 바꾸지 않고 끝낸다. 같은 eventId의 다른 내용이면 격리한다 */
    private Optional<Result> replayIfProcessed(Settlement settlement) {
        return processedOperationRepository.findByOperationId(settlement.eventId()).map(processed -> {
            if (!processed.sameContent(settlement.contentKey())) {
                throw new CouponOrderConsumerIsolationException("같은 eventId에 다른 내용이 왔다 eventId="
                        + settlement.eventId() + ", orderItemId=" + settlement.orderItemId());
            }
            log.debug("이미 처리한 주문 이벤트라 다시 반영하지 않는다 eventId={}", settlement.eventId());
            return Result.DUPLICATE;
        });
    }

    private static void requireDistinctUsages(Settlement settlement) {
        Set<Long> seen = new HashSet<>();
        for (UsageRef usage : settlement.usages()) {
            if (!seen.add(usage.memberCouponId())) {
                throw new CouponOrderConsumerIsolationException("같은 발급 쿠폰이 이벤트에 두 번 들어 있다 eventId="
                        + settlement.eventId() + ", memberCouponId=" + usage.memberCouponId());
            }
        }
    }

    /** 처리 결과 */
    public enum Result {
        /** 한 사용 시도라도 상태를 바꿨다 */
        APPLIED("applied"),
        /** 사실만 남기고 상태는 바꾸지 않았다(순서 역전·이전 generation·이미 같은 결과) */
        FACT_ONLY("fact_only"),
        /** 이미 처리한 이벤트다(아무것도 바꾸지 않았다) */
        DUPLICATE("duplicate");

        private final String metricTag;

        Result(String metricTag) {
            this.metricTag = metricTag;
        }

        String metricTag() {
            return metricTag;
        }
    }

    /** 이벤트의 사용 시도 하나: (발급 쿠폰, generation) */
    private record UsageRef(long memberCouponId, long generation) {
    }

    /**
     * 결제·취소 완료 이벤트의 공통 내용.
     *
     * @param event      지표·로그의 이벤트 이름
     * @param occurredAt 결제 완료 시각 또는 반환 완료 시각
     * @param extraParts 내용 키에 더 넣을 조각(취소 완료의 지갑 작업 ID·예산 복구 결과)
     */
    private record Settlement(String event, String eventId, long orderId, long orderItemId, long memberId,
                              List<UsageRef> usages, Instant occurredAt, List<String> extraParts) {

        /** 같은 eventId로 온 이벤트가 처음과 같은 내용인지 가르는 값. 사용 시도의 순서와 무관하다 */
        String contentKey() {
            List<String> parts = new ArrayList<>();
            parts.add("e" + event);
            parts.add("i" + orderItemId);
            parts.add("t" + occurredAt);
            usages.forEach(usage -> parts.add("u" + usage.memberCouponId() + "|g" + usage.generation()));
            parts.addAll(extraParts);
            return UsageContentKey.of(memberId, orderId, parts);
        }
    }
}
