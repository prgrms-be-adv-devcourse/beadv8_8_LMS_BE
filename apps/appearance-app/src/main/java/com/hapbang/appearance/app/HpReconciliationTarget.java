package com.hapbang.appearance.app;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceRequestStatus;
import com.hapbang.appearance.domain.AppearanceStatus;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.domain.HpTaskType;

/**
 * 서비스 간 HP 대사의 비교 대상 하나 (#581): 지갑 보류 하나에 걸린 요청서 또는 모집글과 그 출연·HP 작업.
 * <p>
 * 출연 상태만으로 계산한 기대값(남은 보류·지급액)과 끝난(DONE) HP 작업의 합을 함께 가진다. 둘을 비교하는 것이 ①단계,
 * 작업의 합을 지갑 기록과 비교하는 것이 ②단계다({@link HpReconciliationComparer}).
 *
 * @param refType           지갑 참조 유형 (요청서·모집글)
 * @param refId             요청서·모집글 ID
 * @param hostId            보류한 호스트
 * @param status            요청서·모집글 상태 이름 (요약용)
 * @param holdFailed        보류 실패로 끝났다({@code HOLD_FAILED}). 지갑에 남은 보류·지급이 없어야 한다
 * @param holdPending       보류 결과를 기다린다({@code HOLD_PENDING}). 비교하지 않는다
 * @param initialHold       보류 요청에 쓴 금액 (요청서 출연료, 모집글 1인당 출연료 × 모집 인원)
 * @param expectedRemaining 상태로 계산한 남은 보류
 * @param expectedPaid      상태로 계산한 지급액 (확인된 출연료 + 위약금)
 * @param appearances       이 보류에서 성립한 출연
 * @param tasks             이 보류의 HP 작업 전부
 * @param changedAt         대상·출연·작업 중 마지막으로 바뀐 시각
 */
record HpReconciliationTarget(
        HpTaskRefType refType,
        Long refId,
        Long hostId,
        String status,
        boolean holdFailed,
        boolean holdPending,
        long initialHold,
        long expectedRemaining,
        long expectedPaid,
        List<Appearance> appearances,
        List<HpTask> tasks,
        LocalDateTime changedAt
) {

    HpReconciliationTarget {
        appearances = List.copyOf(appearances);
        tasks = List.copyOf(tasks);
    }

    /**
     * 요청서. 수락 전에는 출연료 전부가 보류에 남고, 수락 뒤에는 출연 상태를 따른다.
     * 거절·만료·응답 전 취소는 전부 반환돼 남은 보류가 0이다.
     *
     * @param appearance 수락해 성립한 출연. 수락 전이면 null
     */
    static HpReconciliationTarget ofRequest(AppearanceRequest request, Appearance appearance, List<HpTask> tasks) {
        List<Appearance> appearances = appearance == null ? List.of() : List.of(appearance);
        long remaining = switch (request.getStatus()) {
            case HOLD_PENDING, REQUESTED -> request.getFee();
            case ACCEPTED -> appearance == null || isOngoing(appearance) ? request.getFee() : 0;
            case HOLD_FAILED, REJECTED, EXPIRED, CANCELED -> 0;
        };
        return new HpReconciliationTarget(HpTaskRefType.APPEARANCE_REQUEST, request.getId(), request.getHostId(),
                request.getStatus().name(), request.getStatus() == AppearanceRequestStatus.HOLD_FAILED,
                request.getStatus() == AppearanceRequestStatus.HOLD_PENDING, request.getFee(), remaining,
                paidOf(appearances), appearances, tasks, changedAt(request.getUpdatedAt(), appearances, tasks));
    }

    /**
     * 모집글. 보류는 1인당 출연료 × 모집 인원이다.
     * <ul>
     *     <li>모집 중: 끝난 출연(확인·불참·호스트 일방 취소)의 출연료만 빠진다. 모집 중 합의 취소는 자리가 다시 열려 반환하지 않는다</li>
     *     <li>마감·전체 취소 뒤: 빈자리는 반환됐으므로 아직 진행 중인 출연의 출연료만 남는다</li>
     * </ul>
     */
    static HpReconciliationTarget ofRecruit(AppearanceRecruit recruit, List<Appearance> appearances,
                                            List<HpTask> tasks) {
        long initial = Math.multiplyExact(recruit.getFeePerPerson(), recruit.getRecruitCount());
        long remaining = switch (recruit.getStatus()) {
            case HOLD_PENDING -> initial;
            case HOLD_FAILED -> 0;
            case RECRUITING -> initial - feeOf(appearances.stream().filter(HpReconciliationTarget::isFinishedWithTask));
            case CLOSED, CANCELED -> feeOf(appearances.stream().filter(HpReconciliationTarget::isOngoing));
        };
        return new HpReconciliationTarget(HpTaskRefType.APPEARANCE_RECRUIT, recruit.getId(), recruit.getHostId(),
                recruit.getStatus().name(), recruit.getStatus() == AppearanceRecruitStatus.HOLD_FAILED,
                recruit.getStatus() == AppearanceRecruitStatus.HOLD_PENDING, initial, remaining, paidOf(appearances),
                appearances, tasks, changedAt(recruit.getUpdatedAt(), appearances, tasks));
    }

    /** 재시도를 기다리는 작업이 있다 (진행 중이라 비교하지 않는다) */
    boolean hasPendingTask() {
        return tasks.stream().anyMatch(task -> task.getStatus() == HpTaskStatus.PENDING);
    }

    /** 격리된 작업이 있다 (비교하지 않고 표시만 한다) */
    boolean hasDeadTask() {
        return tasks.stream().anyMatch(task -> task.getStatus() == HpTaskStatus.DEAD);
    }

    /** 끝난 작업으로 반환한 금액: 반환 작업 + 위약금 작업에서 호스트에게 돌려준 몫 */
    long taskReleased() {
        return doneTasks().mapToLong(task -> switch (task.getTaskType()) {
            case RELEASE -> task.getAmount();
            case PENALTY -> task.releaseAmountOfPenalty();
            case PAYOUT -> 0;
        }).sum();
    }

    /** 끝난 작업으로 지급한 금액: 지급 작업 + 위약금 */
    long taskPaid() {
        return doneTasks().filter(task -> task.getTaskType() != HpTaskType.RELEASE)
                .mapToLong(HpTask::getPayoutAmount).sum();
    }

    /** 끝난 작업 기준 남은 보류 */
    long taskRemaining() {
        return initialHold - taskReleased() - taskPaid();
    }

    /** 끝난 지급·위약금 작업 (지갑에 지급 건이 하나씩 있어야 한다) */
    List<HpTask> doneSettlementTasks() {
        return doneTasks().filter(task -> task.getTaskType() != HpTaskType.RELEASE).toList();
    }

    private Stream<HpTask> doneTasks() {
        return tasks.stream().filter(task -> task.getStatus() == HpTaskStatus.DONE);
    }

    /** 진행 중인 출연 (보류에 출연료가 남아 있다) */
    private static boolean isOngoing(Appearance appearance) {
        return appearance.getStatus() == AppearanceStatus.ESTABLISHED
                || appearance.getStatus() == AppearanceStatus.CANCEL_REQUESTED;
    }

    /** 출연 단위 작업(지급·반환·위약금)으로 끝나는 출연. 모집 중 합의 취소는 작업 없이 자리를 다시 연다 */
    private static boolean isFinishedWithTask(Appearance appearance) {
        return switch (appearance.getStatus()) {
            case CONFIRMED, ABSENT, CANCELED_BY_HOST -> true;
            case ESTABLISHED, CANCEL_REQUESTED, CANCELED_BY_AGREEMENT -> false;
        };
    }

    private static long feeOf(Stream<Appearance> appearances) {
        return appearances.mapToLong(Appearance::getFee).sum();
    }

    /** 상태로 계산한 지급액: 확인된 출연은 출연료, 호스트 일방 취소는 위약금 */
    private static long paidOf(List<Appearance> appearances) {
        long paid = 0;
        for (Appearance appearance : appearances) {
            if (appearance.getStatus() == AppearanceStatus.CONFIRMED) {
                paid += appearance.getFee();
            } else if (appearance.getStatus() == AppearanceStatus.CANCELED_BY_HOST
                    && appearance.getPenaltyAmount() != null) {
                // 위약금이 비어 있으면 기대 지급을 0으로 두어 ①단계에서 작업과 어긋남으로 드러나게 한다
                paid += appearance.getPenaltyAmount();
            }
        }
        return paid;
    }

    private static LocalDateTime changedAt(LocalDateTime targetUpdatedAt, List<Appearance> appearances,
                                           List<HpTask> tasks) {
        return Stream.concat(Stream.of(targetUpdatedAt),
                        Stream.concat(appearances.stream().map(Appearance::getUpdatedAt),
                                tasks.stream().map(HpTask::getUpdatedAt)))
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }
}
