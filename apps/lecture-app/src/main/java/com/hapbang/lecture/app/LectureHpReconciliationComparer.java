package com.hapbang.lecture.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.hapbang.lecture.domain.LectureHpReconciliationRule;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.shared.billing.domain.BillingSettlementStatus;
import com.hapbang.shared.billing.domain.PayoutType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldStateDto;

/**
 * 서비스 간 HP 대사 비교 규칙 (#580, D74). DB·지갑을 부르지 않는 순수 비교다.
 * <p>
 * 주문 항목 상태로 기대 보류를 정하고 지갑 기록과 한 단계로 비교한다. 강의는 상태 전이와 HP 작업 등록이 같은 트랜잭션이라(D57)
 * 작업의 합을 따로 비교하지 않는다. 강의는 늘 전액 반환·전액 지급이므로 보류 상태 이름 대신 합계 금액 네 개(남은 보류·반환·지급·확정 차감)를
 * 비교한다. 금액이 맞으면 상태(HELD·RELEASED·PAID)도 맞다.
 * <ul>
 *     <li>{@code HOLD_FAILED}: 보류 없음, 무효화, 또는 남은 보류·지급·확정 차감 0. 지급 건 없음</li>
 *     <li>구매·구매 확정·반환 처리 중·지급 처리 중: 최초 = 남은 = 가격, 보류한 회원 = 구매자. 지급 건 없음</li>
 *     <li>{@code CANCELED}·{@code RETURNED}: 최초 = 반환 = 가격, 남은 0. 지급 건 없음</li>
 *     <li>{@code PAID_OUT}: 최초 = 지급 = 가격, 남은 0. 지급 건 하나(원천 = 항목, 일반 지급, 판매자, 가격, COMPLETED)</li>
 * </ul>
 * 결제 단계가 끝나지 않은 항목, 진행 중인 항목, DEAD 작업이 있는 항목은 부르는 쪽이 먼저 뺀다.
 */
final class LectureHpReconciliationComparer {

    private LectureHpReconciliationComparer() {
    }

    /** 어긋난 내용 하나 */
    record Finding(LectureHpReconciliationRule rule, String lectureState, String walletState) {
    }

    /**
     * @throws IllegalArgumentException 결제 단계가 끝나지 않은 항목 (부르는 쪽이 뺀다)
     */
    static List<Finding> compare(LectureHpReconciliationTarget target, HoldStateDto wallet) {
        if (target.holdInProgress()) {
            throw new IllegalArgumentException("결제 단계가 끝나지 않은 항목은 비교하지 않습니다: " + target.orderItemId());
        }
        List<Finding> findings = new ArrayList<>();
        String lectureState = describe(target);
        String walletState = describe(wallet);
        switch (target.status()) {
            case HOLD_FAILED -> compareClosedHold(wallet, lectureState, walletState, findings);
            case PURCHASED, CONFIRMED, CANCEL_PENDING, RETURN_PENDING, PAYOUT_PENDING ->
                    compareActiveHold(target, wallet, target.priceHp(), 0, 0, lectureState, walletState, findings);
            case CANCELED, RETURNED ->
                    compareActiveHold(target, wallet, 0, target.priceHp(), 0, lectureState, walletState, findings);
            case PAID_OUT ->
                    compareActiveHold(target, wallet, 0, 0, target.priceHp(), lectureState, walletState, findings);
            case HOLD_PENDING, HOLD_COMPENSATING -> throw new IllegalStateException("위에서 막았다");
        }
        compareSettlements(target, wallet, lectureState, walletState, findings);
        return findings;
    }

    /** 지갑 목록에는 있는데 강의에 그 주문 항목이 없다 */
    static Finding walletOnly(Long orderItemId, HoldStateDto wallet) {
        return new Finding(LectureHpReconciliationRule.WALLET_ONLY_HOLD, "주문 항목 " + orderItemId + " 없음",
                describe(wallet));
    }

    /** 자동 처리가 멈춘 HP 작업이 있어 비교하지 않는다는 표시 */
    static Finding dead(LectureHpReconciliationTarget target) {
        return new Finding(LectureHpReconciliationRule.HP_TASK_DEAD,
                describe(target) + "; DEAD [" + String.join(", ", target.deadTasks()) + "]", "비교 안 함");
    }

    // 결제 실패: 보류가 없거나, 무효화됐거나, 남은 보류·지급·확정 차감이 모두 0이어야 한다
    private static void compareClosedHold(HoldStateDto wallet, String lectureState, String walletState,
                                          List<Finding> findings) {
        HoldStateDto.Hold hold = wallet.hold();
        if (hold != null && (hold.remaining().totalHp() > 0 || hold.paid().totalHp() > 0
                || hold.captured().totalHp() > 0)) {
            findings.add(new Finding(LectureHpReconciliationRule.HOLD_NOT_CLOSED, lectureState, walletState));
        }
    }

    // 보류가 있어야 하는 항목: 무효화 기록이 없고, 최초 보류 = 가격·보류한 회원 = 구매자, 금액 네 개가 기대값과 같아야 한다
    private static void compareActiveHold(LectureHpReconciliationTarget target, HoldStateDto wallet,
                                          long expectedRemaining, long expectedReleased, long expectedPaid,
                                          String lectureState, String walletState, List<Finding> findings) {
        if (wallet.voided() != null) {
            findings.add(new Finding(LectureHpReconciliationRule.VOIDED_WHILE_ACTIVE, lectureState, walletState));
        }
        HoldStateDto.Hold hold = wallet.hold();
        if (hold == null) {
            findings.add(new Finding(LectureHpReconciliationRule.HOLD_MISSING, lectureState, walletState));
            return;
        }
        if (hold.original().totalHp() != target.priceHp()
                || !Objects.equals(hold.payerUserId(), target.buyerUserId())) {
            findings.add(new Finding(LectureHpReconciliationRule.HOLD_MISMATCH, lectureState, walletState));
        }
        if (hold.remaining().totalHp() != expectedRemaining || hold.released().totalHp() != expectedReleased
                || hold.paid().totalHp() != expectedPaid || hold.captured().totalHp() != 0) {
            findings.add(new Finding(LectureHpReconciliationRule.HOLD_BALANCE_MISMATCH, lectureState, walletState));
        }
    }

    // 지급된 항목만 지급 건 하나(원천 = 이 항목, 일반 지급, 받는 회원 = 판매자, 금액 = 가격, 완료). 나머지 상태는 지급 건이 없어야 한다
    private static void compareSettlements(LectureHpReconciliationTarget target, HoldStateDto wallet,
                                           String lectureState, String walletState, List<Finding> findings) {
        List<HoldStateDto.Settlement> settlements = wallet.settlements();
        if (target.status() != OrderItemStatus.PAID_OUT) {
            for (HoldStateDto.Settlement unexpected : settlements) {
                findings.add(new Finding(LectureHpReconciliationRule.SETTLEMENT_UNEXPECTED, lectureState,
                        describe(unexpected)));
            }
            return;
        }
        if (settlements.isEmpty()) {
            findings.add(new Finding(LectureHpReconciliationRule.SETTLEMENT_MISSING, lectureState, walletState));
        } else if (settlements.size() > 1 || !matches(target, settlements.getFirst())) {
            findings.add(new Finding(LectureHpReconciliationRule.SETTLEMENT_MISMATCH, lectureState, walletState));
        }
    }

    private static boolean matches(LectureHpReconciliationTarget target, HoldStateDto.Settlement settlement) {
        return settlement.sourceType() == SettlementSourceType.LECTURE_ORDER_ITEM
                && Objects.equals(settlement.sourceId(), target.orderItemId())
                && settlement.payoutType() == PayoutType.NORMAL
                && Objects.equals(settlement.receiverUserId(), target.sellerUserId())
                && settlement.paidAmountHp() == target.priceHp()
                && settlement.status() == BillingSettlementStatus.COMPLETED;
    }

    // ---------------------------------------------------------------------------------------------
    // 관리자가 읽는 요약 문자열

    static String describe(LectureHpReconciliationTarget target) {
        return "주문 항목 %d 주문 %d %s 가격 %d 구매자 %d 판매자 %d".formatted(target.orderItemId(), target.orderId(),
                target.status(), target.priceHp(), target.buyerUserId(), target.sellerUserId());
    }

    static String describe(HoldStateDto wallet) {
        HoldStateDto.Hold hold = wallet.hold();
        String holdState = hold == null ? "보류 없음" : "보류 %s 회원 %d 최초 %d 남은 %d 반환 %d 지급 %d 확정 차감 %d".formatted(
                hold.status(), hold.payerUserId(), hold.original().totalHp(), hold.remaining().totalHp(),
                hold.released().totalHp(), hold.paid().totalHp(), hold.captured().totalHp());
        String voided = wallet.voided() == null ? "무효화 없음"
                : "무효화 %s %d".formatted(wallet.voided().outcome(), wallet.voided().releasedHp());
        String settlements = wallet.settlements().stream().map(LectureHpReconciliationComparer::describe)
                .collect(Collectors.joining(", "));
        return "%s; %s; 지급 건 [%s]".formatted(holdState, voided, settlements);
    }

    private static String describe(HoldStateDto.Settlement settlement) {
        return "%s %d %s 회원 %d %d/%d %s".formatted(settlement.sourceType(), settlement.sourceId(),
                settlement.payoutType(), settlement.receiverUserId(), settlement.grossAmountHp(),
                settlement.paidAmountHp(), settlement.status());
    }
}
