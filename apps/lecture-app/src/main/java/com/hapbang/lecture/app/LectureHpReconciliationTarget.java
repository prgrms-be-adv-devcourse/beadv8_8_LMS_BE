package com.hapbang.lecture.app;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskStatus;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.shared.billing.dto.HoldPromotionDto;

/**
 * 서비스 간 HP 대사의 비교 대상 하나: 주문 항목과 그 주문의 HP 작업에서 읽은 값 (#580, D74).
 *
 * @param priceHp        항목 가격(요약 문자열용)
 * @param buyerAmountHp  구매자 금액(가격 − 할인). 지갑 보류의 구매자 몫 기대 금액이다
 * @param sellerPayoutHp 판매자 지급액(가격 − 판매자 부담 할인). 지급 건의 기대 금액이다
 * @param itemVersion    항목 버전. 비교하는 사이에 항목이 바뀌었는지 볼 때 쓴다
 * @param lastChangedAt  마지막 변경 시각: 그 주문의 HP 작업 변경 시각과 항목의 구매·취소·반환·지급 시각 중 가장 늦은 것.
 *                       결제 단계는 주문 단위라(D58) 다른 항목의 작업도 본다. 항목에는 변경 시각 컬럼이 없다
 * @param hasRunningTask 그 주문에 대기(PENDING)·실행 중(PROCESSING)인 HP 작업이 있는지
 * @param deadTasks      이 항목의 자동 처리가 멈춘(DEAD) HP 작업 요약
 */
record LectureHpReconciliationTarget(
        Long orderItemId,
        Long orderId,
        OrderItemStatus status,
        long priceHp,
        long buyerAmountHp,
        long sellerPayoutHp,
        Long buyerUserId,
        Long sellerUserId,
        long itemVersion,
        LocalDateTime lastChangedAt,
        boolean hasRunningTask,
        List<String> deadTasks,
        List<HoldPromotionDto> promotions
) {

    LectureHpReconciliationTarget {
        deadTasks = List.copyOf(deadTasks);
        promotions = List.copyOf(promotions);
    }

    LectureHpReconciliationTarget(Long orderItemId, Long orderId, OrderItemStatus status, long priceHp,
            long buyerAmountHp, long sellerPayoutHp, Long buyerUserId, Long sellerUserId, long itemVersion,
            LocalDateTime lastChangedAt, boolean hasRunningTask, List<String> deadTasks) {
        this(orderItemId, orderId, status, priceHp, buyerAmountHp, sellerPayoutHp, buyerUserId, sellerUserId,
                itemVersion, lastChangedAt, hasRunningTask, deadTasks, List.of());
    }

    long promotionAmountHp() {
        return sellerPayoutHp - buyerAmountHp;
    }

    /**
     * @param orderTasks 그 주문의 HP 작업 전부 (다른 항목의 작업 포함)
     */
    static LectureHpReconciliationTarget of(OrderItem item, Long orderId, Collection<LectureHpTask> orderTasks) {
        return of(item, orderId, orderTasks, List.of());
    }

    static LectureHpReconciliationTarget of(OrderItem item, Long orderId, Collection<LectureHpTask> orderTasks,
                                            List<HoldPromotionDto> promotions) {
        LocalDateTime lastChangedAt = Stream.concat(
                        orderTasks.stream().map(LectureHpTask::getUpdatedAt),
                        Stream.of(item.getPurchasedAt(), item.getCanceledAt(), item.getReturnedAt(),
                                item.getPaidOutAt()))
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        boolean hasRunningTask = orderTasks.stream().anyMatch(task -> task.getStatus() == LectureHpTaskStatus.PENDING
                || task.getStatus() == LectureHpTaskStatus.PROCESSING);
        List<String> deadTasks = orderTasks.stream()
                .filter(task -> item.getId().equals(task.getOrderItemId())
                        && task.getStatus() == LectureHpTaskStatus.DEAD)
                .map(task -> "%s#%d %s".formatted(task.getPurpose(), task.getId(), task.getLastErrorCode()))
                .toList();
        return new LectureHpReconciliationTarget(item.getId(), orderId, item.getStatus(), item.getPriceHp(),
                item.buyerAmountHp(), item.sellerPayoutHp(), item.getBuyerUserId(), item.getSellerUserId(), item.getVersion() == null ? 0 : item.getVersion(),
                lastChangedAt, hasRunningTask, deadTasks, promotions);
    }

    /** 결제 단계가 끝나지 않았다(결제 처리 중·결제 취소 처리 중). 주문 전체의 결과를 기다린다 */
    boolean holdInProgress() {
        return status == OrderItemStatus.HOLD_PENDING || status == OrderItemStatus.HOLD_COMPENSATING;
    }

    /** 지갑에 이 항목의 보류가 있어야 하는지. 구매자·플랫폼 몫이 모두 0일 때만 지갑을 부르지 않았다 */
    boolean expectsWalletHold() {
        return buyerAmountHp > 0 || promotionAmountHp() > 0;
    }

    boolean hasDeadTask() {
        return !deadTasks.isEmpty();
    }

    /** 진행 중이라 비교하지 않는다: 결제 단계, 대기·실행 중인 작업, 유예 시간 안의 변경 */
    boolean isInProgress(LocalDateTime settleCutoff) {
        return holdInProgress() || hasRunningTask
                || (lastChangedAt != null && lastChangedAt.isAfter(settleCutoff));
    }

    /** 비교하는 사이에 바뀌었는지 (항목 버전이나 마지막 변경 시각이 다르다) */
    boolean changedSince(LectureHpReconciliationTarget before) {
        return itemVersion != before.itemVersion || !Objects.equals(lastChangedAt, before.lastChangedAt)
                || status != before.status;
    }
}
