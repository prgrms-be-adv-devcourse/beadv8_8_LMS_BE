package com.hapbang.appearance.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.hapbang.shared.appearance.domain.AppearanceEarningStatus;
import com.hapbang.shared.appearance.domain.AppearanceEarningType;

/**
 * 출연 수익 분류 규칙 (#301, ISSUE.md APR-39). 게스트 기준, 출연 1건 = 수익 1건이다.
 * 출연 상태와 그 출연의 지급·위약금 HP 작업({@code PAYOUT}·{@code PENALTY}) 상태로 지급 상태를 정한다.
 *
 * <table>
 *     <tr><th>출연 상태</th><th>작업</th><th>지급 상태</th><th>종류</th><th>금액</th></tr>
 *     <tr><td>ESTABLISHED</td><td>-</td><td>CONFIRMATION_PENDING</td><td>APPEARANCE_FEE</td><td>출연료</td></tr>
 *     <tr><td>CANCEL_REQUESTED</td><td>-</td><td>ON_HOLD</td><td>APPEARANCE_FEE</td><td>출연료</td></tr>
 *     <tr><td>CONFIRMED</td><td>PENDING / DEAD / DONE</td><td>PROCESSING / FAILED / COMPLETED</td><td>APPEARANCE_FEE</td><td>출연료</td></tr>
 *     <tr><td>CANCELED_BY_HOST</td><td>PENDING / DEAD / DONE</td><td>PROCESSING / FAILED / COMPLETED</td><td>APPEARANCE_PENALTY</td><td>위약금</td></tr>
 *     <tr><td>ABSENT, CANCELED_BY_AGREEMENT</td><td>-</td><td>CANCELED</td><td>APPEARANCE_FEE</td><td>출연료</td></tr>
 * </table>
 *
 * 확인·일방 취소는 상태 변경과 작업 기록을 한 트랜잭션에서 하므로 작업이 없을 수 없다. 그래도 DB 제약이 짝을 보장하지는 않으므로
 * 작업이 없으면 받을 돈이 처리되지 않은 것으로 보고 FAILED로 분류한다 (N2, 숨기지 않는다).
 */
public final class AppearanceEarningRule {

    /** 지급 상태를 정하는 데 쓰는 HP 작업 종류 (반환은 게스트 수익이 아니라 섞지 않는다) */
    public static final Set<HpTaskType> EARNING_TASK_TYPES = EnumSet.of(HpTaskType.PAYOUT, HpTaskType.PENALTY);

    private static final AppearanceEarningType FEE = AppearanceEarningType.APPEARANCE_FEE;
    private static final AppearanceEarningType PENALTY = AppearanceEarningType.APPEARANCE_PENALTY;

    /** 지급·위약금 작업이 있어야 하는 출연 상태 */
    private static final Set<AppearanceStatus> TASK_REQUIRED = EnumSet.of(AppearanceStatus.CONFIRMED,
            AppearanceStatus.CANCELED_BY_HOST);

    private AppearanceEarningRule() {
    }

    /**
     * @param taskStatus 그 출연의 지급·위약금 작업 상태, 작업이 없으면 null (작업이 필요 없는 상태에서는 무시)
     */
    public static Classification classify(AppearanceStatus status, HpTaskStatus taskStatus) {
        return switch (status) {
            case ESTABLISHED -> Classification.of(AppearanceEarningStatus.CONFIRMATION_PENDING, FEE);
            case CANCEL_REQUESTED -> Classification.of(AppearanceEarningStatus.ON_HOLD, FEE);
            case CONFIRMED -> byTask(taskStatus, FEE);
            case CANCELED_BY_HOST -> byTask(taskStatus, PENALTY);
            case ABSENT, CANCELED_BY_AGREEMENT -> Classification.of(AppearanceEarningStatus.CANCELED, FEE);
        };
    }

    private static Classification byTask(HpTaskStatus taskStatus, AppearanceEarningType type) {
        if (taskStatus == null) {
            return new Classification(AppearanceEarningStatus.FAILED, type, true);
        }
        AppearanceEarningStatus status = switch (taskStatus) {
            case PENDING -> AppearanceEarningStatus.PROCESSING;
            case DEAD -> AppearanceEarningStatus.FAILED;
            case DONE -> AppearanceEarningStatus.COMPLETED;
        };
        return Classification.of(status, type);
    }

    /** 수익 금액: 출연료, 일방 취소면 위약금 */
    public static long amountOf(Appearance appearance) {
        if (appearance.getStatus() == AppearanceStatus.CANCELED_BY_HOST) {
            return appearance.getPenaltyAmount() == null ? 0 : appearance.getPenaltyAmount();
        }
        return appearance.getFee();
    }

    /**
     * 고른 지급 상태·종류에 해당하는 (출연 상태, 작업 상태) 조합을 조회 조건으로 바꾼다. {@link #classify}를 모든 조합에 적용해
     * 만들므로 분류 규칙과 조회 조건이 어긋나지 않는다.
     *
     * @param statuses 지급 상태 (비어 있으면 아무것도 고르지 않음)
     * @param type     종류, null이면 둘 다
     */
    public static Condition conditionOf(Collection<AppearanceEarningStatus> statuses, AppearanceEarningType type) {
        Set<AppearanceStatus> plain = EnumSet.noneOf(AppearanceStatus.class);
        Set<AppearanceStatus> withTask = EnumSet.noneOf(AppearanceStatus.class);
        Set<HpTaskStatus> taskStatuses = EnumSet.noneOf(HpTaskStatus.class);
        boolean missingTask = false;
        for (AppearanceStatus status : AppearanceStatus.values()) {
            if (!TASK_REQUIRED.contains(status)) {
                if (matches(classify(status, null), statuses, type)) {
                    plain.add(status);
                }
                continue;
            }
            for (HpTaskStatus taskStatus : HpTaskStatus.values()) {
                if (matches(classify(status, taskStatus), statuses, type)) {
                    withTask.add(status);
                    taskStatuses.add(taskStatus);
                }
            }
            if (matches(classify(status, null), statuses, type)) {
                withTask.add(status);
                missingTask = true;
            }
        }
        return new Condition(plain, withTask, taskStatuses, missingTask);
    }

    private static boolean matches(Classification classification, Collection<AppearanceEarningStatus> statuses,
                                   AppearanceEarningType type) {
        return statuses.contains(classification.status()) && (type == null || classification.type() == type);
    }

    /**
     * @param status      지급 상태
     * @param type        수익 종류
     * @param missingTask 있어야 할 지급·위약금 작업이 없음 (로그로 알린다)
     */
    public record Classification(AppearanceEarningStatus status, AppearanceEarningType type, boolean missingTask) {

        static Classification of(AppearanceEarningStatus status, AppearanceEarningType type) {
            return new Classification(status, type, false);
        }
    }

    /**
     * 조회 조건: {@code 출연 상태 ∈ plain} 또는 {@code 출연 상태 ∈ withTask 이고 작업 상태 ∈ taskStatuses}
     * 또는 ({@code includeMissingTask}이면) {@code 출연 상태 ∈ withTask 이고 작업 없음}.
     * 작업이 필요한 출연 상태(확인·일방 취소)는 작업 상태에 따라 나뉘는 방식이 같아서 이 한 가지 모양으로 표현된다
     * (어긋나면 {@code AppearanceEarningRuleTest}가 잡는다).
     */
    public record Condition(Set<AppearanceStatus> plain, Set<AppearanceStatus> withTask,
                            Set<HpTaskStatus> taskStatuses, boolean includeMissingTask) {

        public boolean isEmpty() {
            return plain.isEmpty() && withTask.isEmpty();
        }

        /** 이 조건이 고르는 조합인지 (작업 없음은 taskStatus null) */
        public boolean accepts(AppearanceStatus status, HpTaskStatus taskStatus) {
            if (plain.contains(status)) {
                return true;
            }
            if (!withTask.contains(status)) {
                return false;
            }
            return taskStatus == null ? includeMissingTask : taskStatuses.contains(taskStatus);
        }

        /** 조회 파라미터용: 비어 있으면 한 값을 채운 목록 (JPQL {@code in ()}을 피하고 함께 넘기는 플래그로 무시한다) */
        public static <E extends Enum<E>> List<E> orPlaceholder(Set<E> values, E placeholder) {
            return values.isEmpty() ? List.of(placeholder) : new ArrayList<>(values);
        }
    }
}
