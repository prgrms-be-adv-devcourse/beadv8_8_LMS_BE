package com.hapbang.lecture.domain;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 주문 응답에 싣는 처리 상태 (module.md "처리 상태", D65 (2)). 내부 작업 상태 대신 업무 의미를 보여 준다.
 * <ul>
 *     <li>결제 단계 주문은 주문 단위 값 하나를 주문과 모든 항목에 싣는다 (D69 (2)).</li>
 *     <li>반환·지급 처리 중 항목은 그 상태에 해당하는 자기 작업으로 정한다. 주문 단위 값은 없다 (D70 (7)).</li>
 *     <li>처리 중이 아닌 항목과 작업이 아직 없는 항목은 없다({@code null}).</li>
 * </ul>
 * 요청 ID가 항목·목적마다 하나라서, 한 항목에서 목적이 같은 작업은 하나뿐이다.
 */
public final class OrderProcessingStatuses {

    private final ProcessingStatus order;
    private final Map<Long, ProcessingStatus> items;

    private OrderProcessingStatuses(ProcessingStatus order, Map<Long, ProcessingStatus> items) {
        this.order = order;
        this.items = items;
    }

    /** 처리 중인 것이 없는 주문 */
    public static OrderProcessingStatuses none() {
        return new OrderProcessingStatuses(null, Map.of());
    }

    /** @param tasks 이 주문의 HP 작업 */
    public static OrderProcessingStatuses of(LectureOrder order, Collection<LectureHpTask> tasks) {
        ProcessingStatus holdStatus = order.getStatus().isHoldInProgress()
                ? OrderHoldTasks.of(tasks).processingStatus() : null;
        Map<Long, ProcessingStatus> items = new HashMap<>();
        for (OrderItem item : order.getItems()) {
            ProcessingStatus status = item.getStatus().isSettlementPending()
                    ? settlementStatus(item, tasks) : holdStatus;
            if (status != null) {
                items.put(item.getId(), status);
            }
        }
        return new OrderProcessingStatuses(holdStatus, items);
    }

    /** 주문 단위 처리 상태. 결제 단계일 때만 있다 */
    public ProcessingStatus order() {
        return order;
    }

    public ProcessingStatus item(Long orderItemId) {
        return items.get(orderItemId);
    }

    private static ProcessingStatus settlementStatus(OrderItem item, Collection<LectureHpTask> tasks) {
        LectureHpTaskPurpose purpose = switch (item.getStatus()) {
            case CANCEL_PENDING -> LectureHpTaskPurpose.CANCEL;
            case RETURN_PENDING -> LectureHpTaskPurpose.RETURN;
            case PAYOUT_PENDING -> LectureHpTaskPurpose.PAYOUT;
            default -> throw new IllegalArgumentException("반환·지급 처리 중이 아닌 항목입니다. status=" + item.getStatus());
        };
        return tasks.stream()
                .filter(task -> task.getOrderItemId().equals(item.getId()) && task.getPurpose() == purpose)
                .findFirst()
                .map(ProcessingStatus::of)
                .orElse(null);
    }
}
