package com.hapbang.appearance.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.hapbang.appearance.domain.HpReconciliationRule;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.domain.HpTaskType;
import com.hapbang.shared.billing.domain.BillingSettlementStatus;
import com.hapbang.shared.billing.domain.PayoutType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldStateDto;

/**
 * 서비스 간 HP 대사 비교 규칙 (#581). DB·지갑을 부르지 않는 순수 비교다.
 * <ol>
 *     <li><b>상태 → HP 작업</b>: 출연 상태로 계산한 남은 보류·지급액이 끝난(DONE) HP 작업의 합과 같은지
 *     ({@link HpReconciliationRule#HP_TASK_MISMATCH}). 상태는 바뀌었는데 작업을 남기지 않은 출연 쪽 누락을 잡는다.</li>
 *     <li><b>HP 작업 → 지갑</b>: 끝난 작업의 반환·지급 합과 지급 건이 지갑 기록과 같은지. 지갑 쪽 반영 누락·중복을 잡는다.</li>
 * </ol>
 * 상태별 공식은 {@link HpReconciliationTarget}에만 있고, 지갑과는 작업의 합으로만 비교한다(모집 중 취소·빈자리 반환·위약금이 같은 식).
 * 진행 중인 건(대기 작업, 유예 시간 안)과 격리 작업이 있는 건은 부르는 쪽이 먼저 뺀다.
 */
final class HpReconciliationComparer {

    private HpReconciliationComparer() {
    }

    /** 어긋난 내용 하나. 출연 하나에 걸린 지급 건 규칙이면 그 출연 ID를 담는다 */
    record Finding(HpReconciliationRule rule, Long appearanceId, String appearanceState, String walletState) {
    }

    static List<Finding> compare(HpReconciliationTarget target, HoldStateDto wallet) {
        List<Finding> findings = new ArrayList<>();
        String appearanceState = describe(target);
        String walletState = describe(wallet);
        compareStateWithTasks(target, appearanceState, walletState, findings);
        if (target.holdFailed()) {
            compareClosedHold(wallet, appearanceState, walletState, findings);
        } else {
            compareActiveHold(target, wallet, appearanceState, walletState, findings);
        }
        compareSettlements(target, wallet, appearanceState, findings);
        return findings;
    }

    /** 지갑 목록에는 있는데 출연에 그 요청서·모집글이 없다 */
    static Finding walletOnly(HpTaskRefType refType, Long refId, HoldStateDto wallet) {
        return new Finding(HpReconciliationRule.WALLET_ONLY_HOLD, null, refType + " " + refId + " 없음",
                describe(wallet));
    }

    /** 격리된 HP 작업이 있어 비교하지 않는다는 표시 */
    static Finding dead(HpReconciliationTarget target) {
        String deadTasks = target.tasks().stream()
                .filter(task -> task.getStatus() == HpTaskStatus.DEAD)
                .map(task -> task.getTaskType() + "#" + task.getId())
                .collect(Collectors.joining(", "));
        return new Finding(HpReconciliationRule.HP_TASK_DEAD, null, describe(target) + "; DEAD [" + deadTasks + "]",
                "비교 안 함");
    }

    // ① 상태로 계산한 기대값 ↔ 끝난 작업의 합
    private static void compareStateWithTasks(HpReconciliationTarget target, String appearanceState,
                                              String walletState, List<Finding> findings) {
        if (target.holdFailed()) {
            // 보류 실패는 보류가 없었거나 무효화로 끝나 HP 작업이 생기지 않는다
            if (target.taskReleased() != 0 || target.taskPaid() != 0) {
                findings.add(new Finding(HpReconciliationRule.HP_TASK_MISMATCH, null, appearanceState, walletState));
            }
            return;
        }
        if (target.expectedRemaining() != target.taskRemaining() || target.expectedPaid() != target.taskPaid()) {
            findings.add(new Finding(HpReconciliationRule.HP_TASK_MISMATCH, null, appearanceState, walletState));
        }
    }

    // ② 보류 실패로 끝난 건: 보류가 없거나, 무효화됐거나, 남은 보류·지급·확정 차감이 모두 0이어야 한다
    private static void compareClosedHold(HoldStateDto wallet, String appearanceState, String walletState,
                                          List<Finding> findings) {
        HoldStateDto.Hold hold = wallet.hold();
        if (hold != null && (hold.remaining().totalHp() > 0 || hold.paid().totalHp() > 0
                || hold.captured().totalHp() > 0)) {
            findings.add(new Finding(HpReconciliationRule.HOLD_NOT_CLOSED, null, appearanceState, walletState));
        }
    }

    // ② 보류가 있어야 하는 건: 무효화 기록이 없고, 최초 보류·보류한 회원, 반환·지급·남은 보류가 작업의 합과 같아야 한다
    private static void compareActiveHold(HpReconciliationTarget target, HoldStateDto wallet, String appearanceState,
                                          String walletState, List<Finding> findings) {
        if (wallet.voided() != null) {
            findings.add(new Finding(HpReconciliationRule.VOIDED_WHILE_ACTIVE, null, appearanceState, walletState));
        }
        HoldStateDto.Hold hold = wallet.hold();
        if (hold == null) {
            findings.add(new Finding(HpReconciliationRule.HOLD_MISSING, null, appearanceState, walletState));
            return;
        }
        if (hold.original().totalHp() != target.initialHold() || !Objects.equals(hold.payerUserId(), target.hostId())) {
            findings.add(new Finding(HpReconciliationRule.HOLD_MISMATCH, null, appearanceState, walletState));
        }
        if (hold.released().totalHp() != target.taskReleased() || hold.paid().totalHp() != target.taskPaid()
                || hold.captured().totalHp() != 0 || hold.remaining().totalHp() != target.taskRemaining()) {
            findings.add(new Finding(HpReconciliationRule.HOLD_BALANCE_MISMATCH, null, appearanceState, walletState));
        }
    }

    // ② 끝난 지급·위약금 작업마다 지급 건 하나 (원천 = 출연 ID, 유형, 받는 회원, 금액, 완료). 남는 지급 건도 잡는다
    private static void compareSettlements(HpReconciliationTarget target, HoldStateDto wallet, String appearanceState,
                                           List<Finding> findings) {
        SettlementSourceType sourceType = sourceTypeOf(target.refType());
        Map<SettlementKey, HoldStateDto.Settlement> walletSettlements = new LinkedHashMap<>();
        for (HoldStateDto.Settlement settlement : wallet.settlements()) {
            walletSettlements.put(new SettlementKey(settlement.sourceType(), settlement.sourceId(),
                    settlement.payoutType()), settlement);
        }
        for (HpTask task : target.doneSettlementTasks()) {
            PayoutType payoutType = task.getTaskType() == HpTaskType.PAYOUT ? PayoutType.NORMAL : PayoutType.PENALTY;
            SettlementKey key = new SettlementKey(sourceType, task.getAppearanceId(), payoutType);
            HoldStateDto.Settlement settlement = walletSettlements.remove(key);
            String taskState = appearanceState + "; " + describe(task);
            if (settlement == null) {
                findings.add(new Finding(HpReconciliationRule.SETTLEMENT_MISSING, task.getAppearanceId(), taskState,
                        describe(wallet)));
            } else if (!matches(task, settlement)) {
                findings.add(new Finding(HpReconciliationRule.SETTLEMENT_MISMATCH, task.getAppearanceId(), taskState,
                        describe(settlement)));
            }
        }
        for (HoldStateDto.Settlement unexpected : walletSettlements.values()) {
            findings.add(new Finding(HpReconciliationRule.SETTLEMENT_UNEXPECTED, unexpected.sourceId(), appearanceState,
                    describe(unexpected)));
        }
    }

    private static boolean matches(HpTask task, HoldStateDto.Settlement settlement) {
        long amount = task.getPayoutAmount();
        return Objects.equals(settlement.receiverUserId(), task.getReceiverId())
                && settlement.grossAmountHp() == amount
                && settlement.paidAmountHp() == amount
                && settlement.status() == BillingSettlementStatus.COMPLETED;
    }

    /** 지급 원천 종류: 요청서 보류면 APPEARANCE_REQUEST, 모집글 보류면 RECRUIT_APPEARANCE ({@link HpTaskExecuteUseCase}와 같다) */
    private static SettlementSourceType sourceTypeOf(HpTaskRefType refType) {
        return refType == HpTaskRefType.APPEARANCE_RECRUIT
                ? SettlementSourceType.RECRUIT_APPEARANCE : SettlementSourceType.APPEARANCE_REQUEST;
    }

    private record SettlementKey(SettlementSourceType sourceType, Long sourceId, PayoutType payoutType) {
    }

    // ---------------------------------------------------------------------------------------------
    // 관리자가 읽는 요약 문자열

    static String describe(HpReconciliationTarget target) {
        String appearances = target.appearances().stream()
                .map(appearance -> appearance.getId() + " " + appearance.getStatus())
                .collect(Collectors.joining(", "));
        return "%s %d %s; 출연 [%s]; 기대 남은 보류 %d·지급 %d; 작업 반환 %d·지급 %d·남은 보류 %d".formatted(
                target.refType(), target.refId(), target.status(), appearances, target.expectedRemaining(),
                target.expectedPaid(), target.taskReleased(), target.taskPaid(), target.taskRemaining());
    }

    static String describe(HoldStateDto wallet) {
        HoldStateDto.Hold hold = wallet.hold();
        String holdState = hold == null ? "보류 없음" : "보류 %s 회원 %d 최초 %d 남은 %d 반환 %d 지급 %d 확정 차감 %d".formatted(
                hold.status(), hold.payerUserId(), hold.original().totalHp(), hold.remaining().totalHp(),
                hold.released().totalHp(), hold.paid().totalHp(), hold.captured().totalHp());
        String voided = wallet.voided() == null ? "무효화 없음"
                : "무효화 %s %d".formatted(wallet.voided().outcome(), wallet.voided().releasedHp());
        String settlements = wallet.settlements().stream().map(HpReconciliationComparer::describe)
                .collect(Collectors.joining(", "));
        return "%s; %s; 지급 건 [%s]".formatted(holdState, voided, settlements);
    }

    private static String describe(HoldStateDto.Settlement settlement) {
        return "%s %d %s 회원 %d %d/%d %s".formatted(settlement.sourceType(), settlement.sourceId(),
                settlement.payoutType(), settlement.receiverUserId(), settlement.grossAmountHp(),
                settlement.paidAmountHp(), settlement.status());
    }

    private static String describe(HpTask task) {
        return "작업 %s#%d 출연 %d 회원 %d %d".formatted(task.getTaskType(), task.getId(), task.getAppearanceId(),
                task.getReceiverId(), task.getPayoutAmount());
    }
}
