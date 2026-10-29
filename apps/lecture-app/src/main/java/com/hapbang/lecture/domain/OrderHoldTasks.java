package com.hapbang.lecture.domain;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 한 주문의 결제 작업(보류 {@code ORDER_HOLD}, 보류 반환 {@code HOLD_COMPENSATION})으로 결제 단계를 판정한다 (D58 (3)).
 * 결제와 관계없는 작업(구매 취소·반환·지급)은 뺀다. 판정만 하고 상태는 바꾸지 않는다.
 */
public final class OrderHoldTasks {

    private final List<LectureHpTask> holds;
    private final List<LectureHpTask> compensations;

    private OrderHoldTasks(List<LectureHpTask> holds, List<LectureHpTask> compensations) {
        this.holds = holds;
        this.compensations = compensations;
    }

    public static OrderHoldTasks of(Collection<LectureHpTask> tasks) {
        return new OrderHoldTasks(
                tasks.stream().filter(task -> task.getPurpose() == LectureHpTaskPurpose.ORDER_HOLD).toList(),
                tasks.stream().filter(task -> task.getPurpose() == LectureHpTaskPurpose.HOLD_COMPENSATION).toList());
    }

    /** 모든 항목의 보류가 성공했는지. 그래야 결제가 끝난다 */
    public boolean allHoldsSucceeded() {
        return !holds.isEmpty() && holds.stream().allMatch(OrderHoldTasks::succeeded);
    }

    /** 성공했지만 아직 반환 작업이 없는 보류. 결제 취소 처리 중이면 반환한다 */
    public List<LectureHpTask> holdsToCompensate() {
        Set<Long> compensatedItemIds = compensations.stream()
                .map(LectureHpTask::getOrderItemId)
                .collect(Collectors.toSet());
        return holds.stream()
                .filter(OrderHoldTasks::succeeded)
                .filter(hold -> !compensatedItemIds.contains(hold.getOrderItemId()))
                .toList();
    }

    /**
     * 결제 실패로 끝낼 수 있는지. 모든 보류가 끝났고, 성공한 보류마다 반환이 끝나 묶여 남은 HP가 없어야 한다.
     * 결과를 모르는 보류가 있으면 성공일 수 있어 끝까지 확인한다
     */
    public boolean isFailureSettled() {
        if (!holds.stream().allMatch(task -> task.getStatus() == LectureHpTaskStatus.COMPLETED)) {
            return false;
        }
        Set<Long> returnedItemIds = compensations.stream()
                .filter(OrderHoldTasks::succeeded)
                .map(LectureHpTask::getOrderItemId)
                .collect(Collectors.toSet());
        return holds.stream()
                .filter(OrderHoldTasks::succeeded)
                .allMatch(hold -> returnedItemIds.contains(hold.getOrderItemId()));
    }

    /**
     * 결제 단계의 처리 상태. 끝나지 않은 결제 작업 중 가장 나쁜 상태이고, 모두 끝났으면 {@code null}이다 (D69 (2))
     */
    public ProcessingStatus processingStatus() {
        return Stream.concat(holds.stream(), compensations.stream())
                .map(ProcessingStatus::of)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private static boolean succeeded(LectureHpTask task) {
        return task.getStatus() == LectureHpTaskStatus.COMPLETED && task.getOutcome() == LectureHpTaskOutcome.SUCCEEDED;
    }
}
