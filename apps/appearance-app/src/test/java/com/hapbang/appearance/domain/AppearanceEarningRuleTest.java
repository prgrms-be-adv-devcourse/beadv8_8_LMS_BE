package com.hapbang.appearance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.appearance.domain.AppearanceEarningRule.Classification;
import com.hapbang.appearance.domain.AppearanceEarningRule.Condition;
import com.hapbang.shared.appearance.domain.AppearanceEarningStatus;
import com.hapbang.shared.appearance.domain.AppearanceEarningType;

/**
 * 출연 수익 분류 규칙 (#301, ISSUE.md APR-39). 출연 상태 6개 × 작업 {없음, PENDING, DONE, DEAD} 전 조합을 본다.
 */
class AppearanceEarningRuleTest {

    private static final List<HpTaskStatus> TASK_CASES = taskCases();

    private static List<HpTaskStatus> taskCases() {
        List<HpTaskStatus> cases = new ArrayList<>();
        cases.add(null);
        cases.addAll(Arrays.asList(HpTaskStatus.values()));
        return cases;
    }

    private static void assertClassified(AppearanceStatus status, HpTaskStatus task, AppearanceEarningStatus expected,
                                         AppearanceEarningType type, boolean missingTask) {
        assertThat(AppearanceEarningRule.classify(status, task)).as("%s + %s", status, task)
                .isEqualTo(new Classification(expected, type, missingTask));
    }

    @Test
    @DisplayName("작업이 필요 없는 상태는 작업과 상관없이: 성립 CONFIRMATION_PENDING, 취소 요청 중 ON_HOLD, 불참·합의 취소 CANCELED (모두 출연료)")
    void withoutTask() {
        for (HpTaskStatus task : TASK_CASES) {
            assertClassified(AppearanceStatus.ESTABLISHED, task, AppearanceEarningStatus.CONFIRMATION_PENDING,
                    AppearanceEarningType.APPEARANCE_FEE, false);
            assertClassified(AppearanceStatus.CANCEL_REQUESTED, task, AppearanceEarningStatus.ON_HOLD,
                    AppearanceEarningType.APPEARANCE_FEE, false);
            assertClassified(AppearanceStatus.ABSENT, task, AppearanceEarningStatus.CANCELED,
                    AppearanceEarningType.APPEARANCE_FEE, false);
            assertClassified(AppearanceStatus.CANCELED_BY_AGREEMENT, task, AppearanceEarningStatus.CANCELED,
                    AppearanceEarningType.APPEARANCE_FEE, false);
        }
    }

    @Test
    @DisplayName("확인은 출연료, 일방 취소는 위약금으로: 작업 PENDING → PROCESSING, DEAD → FAILED, DONE → COMPLETED")
    void byTask() {
        for (AppearanceStatus status : List.of(AppearanceStatus.CONFIRMED, AppearanceStatus.CANCELED_BY_HOST)) {
            AppearanceEarningType type = status == AppearanceStatus.CONFIRMED
                    ? AppearanceEarningType.APPEARANCE_FEE : AppearanceEarningType.APPEARANCE_PENALTY;
            assertClassified(status, HpTaskStatus.PENDING, AppearanceEarningStatus.PROCESSING, type, false);
            assertClassified(status, HpTaskStatus.DEAD, AppearanceEarningStatus.FAILED, type, false);
            assertClassified(status, HpTaskStatus.DONE, AppearanceEarningStatus.COMPLETED, type, false);
        }
    }

    @Test
    @DisplayName("확인·일방 취소인데 작업이 없으면 임의로 처리 중·완료로 보지 않고 FAILED + 작업 없음 표시 (N2)")
    void missingTask() {
        assertClassified(AppearanceStatus.CONFIRMED, null, AppearanceEarningStatus.FAILED, AppearanceEarningType.APPEARANCE_FEE, true);
        assertClassified(AppearanceStatus.CANCELED_BY_HOST, null, AppearanceEarningStatus.FAILED,
                AppearanceEarningType.APPEARANCE_PENALTY, true);
    }

    @Test
    @DisplayName("위 두 테스트가 모든 출연 상태를 다룬다 (상태가 추가되면 switch 식이 컴파일을 막고, 이 테스트가 분류 기대값을 추가하게 한다)")
    void everyStatusClassified() {
        assertThat(AppearanceStatus.values()).containsExactlyInAnyOrder(
                AppearanceStatus.ESTABLISHED, AppearanceStatus.CANCEL_REQUESTED, AppearanceStatus.ABSENT,
                AppearanceStatus.CANCELED_BY_AGREEMENT, // withoutTask
                AppearanceStatus.CONFIRMED, AppearanceStatus.CANCELED_BY_HOST); // byTask·missingTask
    }

    @Test
    @DisplayName("조회 조건은 분류 규칙과 어긋나지 않는다: 지급 상태 128개 부분집합 × 종류 3개 모두, 조건이 고르는 조합 = 분류가 그 상태·종류인 조합")
    void conditionMatchesClassify() {
        AppearanceEarningStatus[] all = AppearanceEarningStatus.values();
        List<AppearanceEarningType> types = new ArrayList<>(Arrays.asList(AppearanceEarningType.values()));
        types.add(null);
        for (int mask = 0; mask < (1 << all.length); mask++) {
            Set<AppearanceEarningStatus> statuses = EnumSet.noneOf(AppearanceEarningStatus.class);
            for (int i = 0; i < all.length; i++) {
                if ((mask & (1 << i)) != 0) {
                    statuses.add(all[i]);
                }
            }
            for (AppearanceEarningType type : types) {
                Condition condition = AppearanceEarningRule.conditionOf(statuses, type);
                for (AppearanceStatus status : AppearanceStatus.values()) {
                    for (HpTaskStatus task : TASK_CASES) {
                        Classification classification = AppearanceEarningRule.classify(status, task);
                        boolean expected = statuses.contains(classification.status())
                                && (type == null || classification.type() == type);
                        assertThat(condition.accepts(status, task))
                                .as("statuses=%s type=%s / %s + %s", statuses, type, status, task)
                                .isEqualTo(expected);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("고른 상태가 없거나 출연에 없는 조합이면 조건이 비어 조회하지 않는다 (예: 지급 예정, 위약금 + 확인 대기)")
    void emptyCondition() {
        assertThat(AppearanceEarningRule.conditionOf(Set.of(), null).isEmpty()).isTrue();
        assertThat(AppearanceEarningRule.conditionOf(Set.of(AppearanceEarningStatus.SCHEDULED), null).isEmpty()).isTrue();
        assertThat(AppearanceEarningRule.conditionOf(Set.of(AppearanceEarningStatus.CONFIRMATION_PENDING),
                AppearanceEarningType.APPEARANCE_PENALTY).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("지급 상태를 정하는 작업은 지급·위약금뿐이다 (반환은 호스트에게 돌아가는 돈이라 게스트 수익에 섞지 않는다)")
    void earningTaskTypes() {
        assertThat(AppearanceEarningRule.EARNING_TASK_TYPES)
                .containsExactlyInAnyOrder(HpTaskType.PAYOUT, HpTaskType.PENALTY);
    }
}
