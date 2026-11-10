package com.hapbang.lecture.domain;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 한 주문의 결제 작업(보류 {@code ORDER_HOLD}, 보류 반환 {@code HOLD_COMPENSATION}, 보류 무효화 {@code HOLD_VOID})으로
 * 결제 단계를 판정한다 (D58 (3), D73 (3)). 결제와 관계없는 작업(구매 취소·반환·지급)은 뺀다. 판정만 하고 상태는 바꾸지 않는다.
 */
public final class OrderHoldTasks {

    private final List<LectureHpTask> holds;
    private final List<LectureHpTask> compensations;
    private final List<LectureHpTask> voids;

    private OrderHoldTasks(List<LectureHpTask> holds, List<LectureHpTask> compensations, List<LectureHpTask> voids) {
        this.holds = holds;
        this.compensations = compensations;
        this.voids = voids;
    }

    public static OrderHoldTasks of(Collection<LectureHpTask> tasks) {
        return new OrderHoldTasks(
                tasks.stream().filter(task -> task.getPurpose() == LectureHpTaskPurpose.ORDER_HOLD).toList(),
                tasks.stream().filter(task -> task.getPurpose() == LectureHpTaskPurpose.HOLD_COMPENSATION).toList(),
                tasks.stream().filter(task -> task.getPurpose() == LectureHpTaskPurpose.HOLD_VOID).toList());
    }

    /**
     * 보류가 필요한 모든 항목의 보류가 성공했는지. 그래야 결제가 끝난다. 보류가 필요 없는 항목(구매자 금액 0)은 성공으로 본다.
     * 보류가 필요한 항목이 하나도 없으면 바로 끝난다.
     *
     * @param itemIdsNeedingHold 보류가 필요한 항목 ID ({@link OrderItem#requiresBuyerHpTask()})
     */
    public boolean allHoldsSucceeded(Collection<Long> itemIdsNeedingHold) {
        Set<Long> succeededItemIds = succeededItemIds(holds);
        return succeededItemIds.containsAll(itemIdsNeedingHold);
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
     * 결제 실패로 끝낼 수 있는지. 모든 보류가 끝났고, 성공한 보류마다 반환이, 보류 무효화로 끝낸 보류마다 무효화가 끝나
     * 묶여 남은 HP가 없어야 한다. 결과를 모르는 보류가 있으면 성공일 수 있어 끝까지 확인한다
     */
    public boolean isFailureSettled() {
        if (!holds.stream().allMatch(task -> task.getStatus() == LectureHpTaskStatus.COMPLETED)) {
            return false;
        }
        Set<Long> returnedItemIds = succeededItemIds(compensations);
        Set<Long> voidedItemIds = succeededItemIds(voids);
        return holds.stream().allMatch(hold -> switch (hold.getOutcome()) {
            case SUCCEEDED -> returnedItemIds.contains(hold.getOrderItemId());
            case VOIDED -> voidedItemIds.contains(hold.getOrderItemId());
            case REJECTED, SKIPPED -> true;
        });
    }

    /**
     * 결제 단계의 처리 상태. 끝나지 않은 결제 작업 중 가장 나쁜 상태이고, 모두 끝났으면 {@code null}이다 (D69 (2), D73 (3))
     */
    public ProcessingStatus processingStatus() {
        return Stream.of(holds, compensations, voids)
                .flatMap(List::stream)
                .map(ProcessingStatus::of)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private static Set<Long> succeededItemIds(List<LectureHpTask> tasks) {
        return tasks.stream()
                .filter(OrderHoldTasks::succeeded)
                .map(LectureHpTask::getOrderItemId)
                .collect(Collectors.toSet());
    }

    private static boolean succeeded(LectureHpTask task) {
        return task.getStatus() == LectureHpTaskStatus.COMPLETED && task.getOutcome() == LectureHpTaskOutcome.SUCCEEDED;
    }
}
