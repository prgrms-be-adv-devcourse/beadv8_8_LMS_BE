package com.hapbang.lecture.app;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureHpReconciliationCursor;
import com.hapbang.lecture.domain.LectureHpReconciliationMismatch;
import com.hapbang.lecture.domain.LectureHpReconciliationRule;
import com.hapbang.lecture.domain.LectureHpReconciliationRun;
import com.hapbang.lecture.domain.LectureHpReconciliationStatus;
import com.hapbang.lecture.domain.LectureHpReconciliationTrigger;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.OrderItemCoupon;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.out.LectureHpReconciliationCursorRepository;
import com.hapbang.lecture.out.LectureHpReconciliationMismatchRepository;
import com.hapbang.lecture.out.LectureHpReconciliationRunRepository;
import com.hapbang.lecture.out.LectureHpTaskRepository;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.OrderItemCouponRepository;
import com.hapbang.lecture.out.OrderItemWithOrderId;
import com.hapbang.shared.billing.domain.BillingReferenceType;

import lombok.RequiredArgsConstructor;

/**
 * 서비스 간 HP 대사의 DB 읽기·기록 (#580, D74). 메서드마다 트랜잭션을 연다. 지갑 호출은 이 클래스 밖(트랜잭션 밖)에서 한다.
 */
@Service
@RequiredArgsConstructor
public class LectureHpReconciliationSupport {

    /** 앱이 멈춰 실행 중으로 남은 실행을 정리할 때 남기는 사유 */
    static final String ABANDONED_REASON = "앱이 멈춰 끝나지 못한 실행";

    private static final BillingReferenceType REFERENCE_TYPE = BillingReferenceType.LECTURE_ORDER_ITEM;

    private final LectureHpReconciliationRunRepository runRepository;
    private final LectureHpReconciliationMismatchRepository mismatchRepository;
    private final LectureHpReconciliationCursorRepository cursorRepository;
    private final LectureOrderRepository lectureOrderRepository;
    private final LectureHpTaskRepository lectureHpTaskRepository;
    private final OrderItemCouponRepository orderItemCouponRepository;

    /** 지갑 보류 목록 커서. 저장한 적이 없으면 처음부터 ({@link Instant#EPOCH}, 0) */
    record Cursor(Instant updatedAfter, long afterId) {

        static final Cursor START = new Cursor(Instant.EPOCH, 0);
    }

    /** 남길 어긋남 하나: 대상(지갑에만 있는 보류면 항목 ID만) + 비교 결과 */
    record Mismatch(Long orderItemId, Long orderId, OrderItemStatus itemStatus,
                    LectureHpReconciliationComparer.Finding finding) {

        static Mismatch of(LectureHpReconciliationTarget target, LectureHpReconciliationComparer.Finding finding) {
            return new Mismatch(target.orderItemId(), target.orderId(), target.status(), finding);
        }
    }

    /**
     * 실행을 시작한다. 앱이 멈춰 실행 중으로 남은 실행은 먼저 {@code INCOMPLETE}로 끝낸다(강의 앱 1대·maxSurge 0 전제,
     * 실행 중 표시는 앱 메모리).
     *
     * @return 새 실행 ID
     */
    @Transactional
    public Long start(LectureHpReconciliationTrigger trigger, Long adminUserId, LocalDateTime now) {
        for (LectureHpReconciliationRun abandoned : runRepository.findByStatus(LectureHpReconciliationStatus.RUNNING)) {
            abandoned.incomplete(abandoned.getCheckedCount(), abandoned.getMismatchCount(), abandoned.getDeadCount(),
                    ABANDONED_REASON, now);
        }
        return runRepository.save(LectureHpReconciliationRun.start(trigger, adminUserId, now)).getId();
    }

    @Transactional(readOnly = true)
    public Cursor cursor() {
        return cursorRepository.findById(REFERENCE_TYPE)
                .map(cursor -> new Cursor(cursor.getUpdatedAfter().atZone(LectureConfig.ZONE).toInstant(),
                        cursor.getAfterId()))
                .orElse(Cursor.START);
    }

    /** 이 시각 이후 HP 작업이 바뀐 주문 항목 ID */
    @Transactional(readOnly = true)
    public Set<Long> findChangedItemIds(LocalDateTime since) {
        return new TreeSet<>(lectureHpTaskRepository.findOrderItemIdsUpdatedSince(since));
    }

    /**
     * 주문 항목과 그 주문의 HP 작업을 읽어 비교 대상으로 만든다.
     *
     * @return 주문 항목 ID → 비교 대상. 강의에 없는 ID는 빠진다(지갑에만 있는 보류)
     */
    @Transactional(readOnly = true)
    public Map<Long, LectureHpReconciliationTarget> loadTargets(Collection<Long> orderItemIds) {
        List<OrderItemWithOrderId> items = lectureOrderRepository.findItemsWithOrderId(orderItemIds);
        Set<Long> orderIds = items.stream().map(OrderItemWithOrderId::orderId).collect(Collectors.toSet());
        Map<Long, List<LectureHpTask>> tasksByOrder = orderIds.isEmpty() ? Map.of()
                : lectureHpTaskRepository.findByOrderIdIn(orderIds).stream()
                .collect(Collectors.groupingBy(LectureHpTask::getOrderId));
        Map<Long, List<OrderItemCoupon>> coupons = orderItemCouponRepository
                .findByOrderItemIdInOrderByOrderItemIdAscApplyOrderAsc(orderItemIds).stream()
                .collect(Collectors.groupingBy(OrderItemCoupon::getOrderItemId));
        Map<Long, LectureHpReconciliationTarget> targets = new LinkedHashMap<>();
        for (OrderItemWithOrderId row : items) {
            targets.put(row.item().getId(), LectureHpReconciliationTarget.of(row.item(), row.orderId(),
                    tasksByOrder.getOrDefault(row.orderId(), List.of()),
                    // 예약 전·예약 거절 항목에는 generation이 없다. 완료된 보류에서 빠진 예약은 비교기의 금액 합으로 잡는다.
                    LectureHpPromotions.of(coupons.getOrDefault(row.item().getId(), List.of()).stream()
                            .filter(coupon -> coupon.getReservedGeneration() != null).toList())));
        }
        return targets;
    }

    /**
     * 실행을 끝내고 어긋난 건을 남긴다. 끝까지 비교했으면 커서를 앞으로 옮긴다.
     *
     * @param failureReason 끝까지 비교하지 못한 이유. null이면 {@code COMPLETED}
     * @param cursor        끝까지 읽은 지갑 보류 목록 커서. 완료일 때만 저장한다
     */
    @Transactional
    public LectureHpReconciliationRun finish(Long runId, List<Mismatch> mismatches, int checkedCount, int deadCount,
                                             String failureReason, Cursor cursor, LocalDateTime now) {
        LectureHpReconciliationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("대사 실행이 없습니다: " + runId));
        for (Mismatch mismatch : mismatches) {
            LectureHpReconciliationComparer.Finding finding = mismatch.finding();
            mismatchRepository.save(new LectureHpReconciliationMismatch(runId, mismatch.orderItemId(),
                    mismatch.orderId(), finding.rule(), mismatch.itemStatus(), finding.lectureState(),
                    finding.walletState(), now));
        }
        int mismatchCount = mismatches.size() - deadCount;
        if (failureReason != null || cursor == null) {
            run.incomplete(checkedCount, mismatchCount, deadCount,
                    failureReason == null ? "지갑 보류 목록을 끝까지 읽지 못함" : failureReason, now);
            return run;
        }
        run.complete(checkedCount, mismatchCount, deadCount, now);
        LocalDateTime updatedAfter = LocalDateTime.ofInstant(cursor.updatedAfter(), LectureConfig.ZONE);
        cursorRepository.findById(REFERENCE_TYPE).ifPresentOrElse(
                saved -> saved.moveTo(updatedAfter, cursor.afterId()),
                () -> cursorRepository.save(
                        new LectureHpReconciliationCursor(REFERENCE_TYPE, updatedAfter, cursor.afterId())));
        return run;
    }

    @Transactional(readOnly = true)
    public LectureHpReconciliationRun findLatestRun() {
        return runRepository.findFirstByOrderByStartedAtDescIdDesc().orElse(null);
    }

    @Transactional(readOnly = true)
    public List<LectureHpReconciliationMismatch> findMismatches(Long runId) {
        return mismatchRepository.findByRunIdOrderByIdAsc(runId);
    }

    /**
     * 앱이 대사 도중 멈춰 실행 중으로 남은 실행을 기동할 때 미완료로 끝낸다 (#805). {@link #start}도 같은 정리를 하지만 다음 실행 때라
     * 그때까지 중단이 지표에 드러나지 않는다. 강의 앱 1대·maxSurge 0이라 기동할 때 다른 pod가 대사 중일 수 없다. 이 앱이 뜬 뒤 시작한
     * 실행(기동 직후 배치)은 건드리지 않도록 {@code startedBefore} 전에 시작한 것만 끝낸다.
     *
     * @return 끝낸 실행 ID
     */
    @Transactional
    public List<Long> abandonRunningStartedBefore(LocalDateTime startedBefore, LocalDateTime now) {
        List<Long> abandonedIds = new ArrayList<>();
        for (LectureHpReconciliationRun run : runRepository.findByStatus(LectureHpReconciliationStatus.RUNNING)) {
            if (run.getStartedAt().isBefore(startedBefore)) {
                run.incomplete(run.getCheckedCount(), run.getMismatchCount(), run.getDeadCount(), ABANDONED_REASON,
                        now);
                abandonedIds.add(run.getId());
            }
        }
        return abandonedIds;
    }

    /** 끝까지 비교한 실행 하나의 지표 값: 끝난 시각과 규칙별 건수({@code HP_TASK_DEAD} 표시 포함) */
    record CompletedRun(Long runId, LocalDateTime finishedAt, Map<LectureHpReconciliationRule, Long> counts) {
    }

    /** 마지막으로 끝까지 비교한({@code COMPLETED}) 실행. 기동할 때 지표를 채우는 데 쓴다 (#805) */
    @Transactional(readOnly = true)
    public Optional<CompletedRun> findLatestCompletedRun() {
        return runRepository.findFirstByStatusOrderByFinishedAtDescIdDesc(LectureHpReconciliationStatus.COMPLETED)
                .map(run -> {
                    Map<LectureHpReconciliationRule, Long> counts = new EnumMap<>(LectureHpReconciliationRule.class);
                    for (LectureHpReconciliationMismatch mismatch : mismatchRepository.findByRunIdOrderByIdAsc(
                            run.getId())) {
                        counts.merge(mismatch.getRule(), 1L, Long::sum);
                    }
                    return new CompletedRun(run.getId(), run.getFinishedAt(), counts);
                });
    }
}
