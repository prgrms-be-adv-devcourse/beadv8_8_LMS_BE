package com.hapbang.lecture.app;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.hapbang.lecture.domain.LectureHpReconciliationRule;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.shared.billing.domain.BillingHoldStatus;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.BillingSettlementStatus;
import com.hapbang.shared.billing.domain.HoldVoidOutcome;
import com.hapbang.shared.billing.domain.PayoutType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldStateDto;
import com.hapbang.shared.billing.dto.HoldPromotionDto;

/**
 * 서비스 간 HP 대사 비교 규칙 (#580, D74). DB·지갑 없이 항목 상태와 지갑 기록만으로 비교한다.
 * 지갑 기록은 테스트가 손으로 적는다(강의 코드로 계산하지 않음). 항목 가격 10,000 HP.
 */
class LectureHpReconciliationComparerTest {

    private static final long PRICE = 10_000L;
    private static final Long ITEM_ID = 31L;
    private static final Long ORDER_ID = 3L;
    private static final Long BUYER_ID = 2L;
    private static final Long SELLER_ID = 1L;

    @ParameterizedTest
    @EnumSource(value = OrderItemStatus.class,
            names = {"PURCHASED", "CONFIRMED", "CANCEL_PENDING", "RETURN_PENDING", "PAYOUT_PENDING"})
    void 진행_중인_거래는_보류가_가격만큼_남아_있으면_정상이다(OrderItemStatus status) {
        assertThat(LectureHpReconciliationComparer.compare(target(status), held())).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = OrderItemStatus.class, names = {"CANCELED", "RETURNED"})
    void 취소_반환은_보류가_전부_반환됐으면_정상이다(OrderItemStatus status) {
        assertThat(LectureHpReconciliationComparer.compare(target(status), released())).isEmpty();
    }

    @Test
    void 지급은_보류가_전부_지급되고_판매자에게_지급_건이_하나_있으면_정상이다() {
        assertThat(LectureHpReconciliationComparer.compare(target(OrderItemStatus.PAID_OUT),
                paid(settlement(SELLER_ID, PRICE, BillingSettlementStatus.COMPLETED)))).isEmpty();
    }

    @Test
    void 플랫폼_없는_지급도_구매자_재원_응답이_있으면_그_금액을_대조한다() {
        var settlement = new HoldStateDto.Settlement(SettlementSourceType.LECTURE_ORDER_ITEM, ITEM_ID,
                PayoutType.NORMAL, SELLER_ID, PRICE, PRICE, BillingSettlementStatus.COMPLETED, PRICE - 1, 0L);

        assertThat(rules(LectureHpReconciliationComparer.compare(target(OrderItemStatus.PAID_OUT), paid(settlement))))
                .containsExactly(LectureHpReconciliationRule.SETTLEMENT_MISMATCH);
    }

    @Test
    void 결제_실패는_보류가_없거나_무효화됐거나_전부_반환됐으면_정상이다() {
        LectureHpReconciliationTarget failed = target(OrderItemStatus.HOLD_FAILED);

        assertThat(LectureHpReconciliationComparer.compare(failed, empty())).isEmpty();
        assertThat(LectureHpReconciliationComparer.compare(failed,
                new HoldStateDto(BillingReferenceType.LECTURE_ORDER_ITEM, ITEM_ID, null,
                        new HoldStateDto.Voided(HoldVoidOutcome.VOIDED, 0), List.of()))).isEmpty();
        assertThat(LectureHpReconciliationComparer.compare(failed, released())).isEmpty();
    }

    @Test
    void 취소됐는데_보류가_HELD면_HOLD_BALANCE_MISMATCH다() {
        assertThat(rules(LectureHpReconciliationComparer.compare(target(OrderItemStatus.CANCELED), held())))
                .containsExactly(LectureHpReconciliationRule.HOLD_BALANCE_MISMATCH);
    }

    @Test
    void 지급됐는데_지급_건이_없으면_SETTLEMENT_MISSING이다() {
        assertThat(rules(LectureHpReconciliationComparer.compare(target(OrderItemStatus.PAID_OUT), paid())))
                .containsExactly(LectureHpReconciliationRule.SETTLEMENT_MISSING);
    }

    @Test
    void 보류_금액이_항목_가격과_다르면_HOLD_MISMATCH다() {
        HoldStateDto wallet = state(hold(BillingHoldStatus.HELD, BUYER_ID, PRICE + 1, PRICE + 1, 0, 0), null,
                List.of());

        assertThat(rules(LectureHpReconciliationComparer.compare(target(OrderItemStatus.PURCHASED), wallet)))
                .contains(LectureHpReconciliationRule.HOLD_MISMATCH);
    }

    @Test
    void 보류한_회원이_구매자가_아니면_HOLD_MISMATCH다() {
        HoldStateDto wallet = state(hold(BillingHoldStatus.HELD, 99L, PRICE, PRICE, 0, 0), null, List.of());

        assertThat(rules(LectureHpReconciliationComparer.compare(target(OrderItemStatus.CONFIRMED), wallet)))
                .containsExactly(LectureHpReconciliationRule.HOLD_MISMATCH);
    }

    @Test
    void 보류가_있어야_하는데_없으면_HOLD_MISSING이다() {
        assertThat(rules(LectureHpReconciliationComparer.compare(target(OrderItemStatus.PURCHASED), empty())))
                .containsExactly(LectureHpReconciliationRule.HOLD_MISSING);
    }

    @Test
    void 결제_실패인데_남은_보류가_있으면_HOLD_NOT_CLOSED다() {
        assertThat(rules(LectureHpReconciliationComparer.compare(target(OrderItemStatus.HOLD_FAILED), held())))
                .containsExactly(LectureHpReconciliationRule.HOLD_NOT_CLOSED);
    }

    @Test
    void 결제_실패가_아닌데_무효화_기록이_있으면_VOIDED_WHILE_ACTIVE다() {
        HoldStateDto wallet = state(hold(BillingHoldStatus.HELD, BUYER_ID, PRICE, PRICE, 0, 0),
                new HoldStateDto.Voided(HoldVoidOutcome.ALREADY_CLOSED, 0), List.of());

        assertThat(rules(LectureHpReconciliationComparer.compare(target(OrderItemStatus.PURCHASED), wallet)))
                .containsExactly(LectureHpReconciliationRule.VOIDED_WHILE_ACTIVE);
    }

    @Test
    void 지급_건의_받는_회원이나_금액이나_상태가_다르면_SETTLEMENT_MISMATCH다() {
        LectureHpReconciliationTarget paidOut = target(OrderItemStatus.PAID_OUT);

        assertThat(rules(LectureHpReconciliationComparer.compare(paidOut,
                paid(settlement(99L, PRICE, BillingSettlementStatus.COMPLETED)))))
                .containsExactly(LectureHpReconciliationRule.SETTLEMENT_MISMATCH);
        assertThat(rules(LectureHpReconciliationComparer.compare(paidOut,
                paid(settlement(SELLER_ID, PRICE - 1, BillingSettlementStatus.COMPLETED)))))
                .containsExactly(LectureHpReconciliationRule.SETTLEMENT_MISMATCH);
        assertThat(rules(LectureHpReconciliationComparer.compare(paidOut,
                paid(settlement(SELLER_ID, PRICE, BillingSettlementStatus.SCHEDULED)))))
                .containsExactly(LectureHpReconciliationRule.SETTLEMENT_MISMATCH);
    }

    @Test
    void 지급_건이_두_건이면_SETTLEMENT_MISMATCH다() {
        HoldStateDto.Settlement settlement = settlement(SELLER_ID, PRICE, BillingSettlementStatus.COMPLETED);

        assertThat(rules(LectureHpReconciliationComparer.compare(target(OrderItemStatus.PAID_OUT),
                paid(settlement, settlement)))).containsExactly(LectureHpReconciliationRule.SETTLEMENT_MISMATCH);
    }

    @Test
    void 지급_전인데_지급_건이_있으면_SETTLEMENT_UNEXPECTED다() {
        HoldStateDto wallet = state(hold(BillingHoldStatus.HELD, BUYER_ID, PRICE, PRICE, 0, 0), null,
                List.of(settlement(SELLER_ID, PRICE, BillingSettlementStatus.SCHEDULED)));

        assertThat(rules(LectureHpReconciliationComparer.compare(target(OrderItemStatus.CONFIRMED), wallet)))
                .containsExactly(LectureHpReconciliationRule.SETTLEMENT_UNEXPECTED);
    }

    @Test
    void 판매자_쿠폰_항목은_보류가_구매자_금액이고_지급_건이_판매자_지급액이면_정상이다() {
        long discounted = PRICE - 3_000L;
        HoldStateDto wallet = state(hold(BillingHoldStatus.PAID, BUYER_ID, discounted, 0, 0, discounted), null,
                List.of(settlement(SELLER_ID, discounted, BillingSettlementStatus.COMPLETED)));

        assertThat(LectureHpReconciliationComparer.compare(target(OrderItemStatus.PAID_OUT, discounted, discounted),
                wallet)).isEmpty();
        // 할인 전 가격으로 보류됐으면 어긋난다
        assertThat(rules(LectureHpReconciliationComparer.compare(target(OrderItemStatus.PURCHASED, discounted,
                discounted), held()))).contains(LectureHpReconciliationRule.HOLD_MISMATCH);
    }

    @ParameterizedTest
    @EnumSource(value = OrderItemStatus.class, names = {"PURCHASED", "CANCELED", "RETURNED", "PAID_OUT"})
    void 구매자_금액이_0인_항목은_지갑에_보류와_지급_건이_없어야_정상이다(OrderItemStatus status) {
        LectureHpReconciliationTarget free = target(status, 0, 0);

        assertThat(LectureHpReconciliationComparer.compare(free, empty())).isEmpty();
        assertThat(rules(LectureHpReconciliationComparer.compare(free, held())))
                .containsExactly(LectureHpReconciliationRule.HOLD_NOT_CLOSED);
    }

    @Test
    void 결제_단계가_끝나지_않은_항목은_비교하지_않는다() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                LectureHpReconciliationComparer.compare(target(OrderItemStatus.HOLD_PENDING), held()));
    }

    @Test
    void 요약_문자열에_항목과_지갑_상태를_담는다() {
        LectureHpReconciliationComparer.Finding finding =
                LectureHpReconciliationComparer.compare(target(OrderItemStatus.CANCELED), held()).getFirst();

        assertThat(finding.lectureState()).contains("주문 항목 31", "주문 3", "CANCELED", "가격 10000");
        assertThat(finding.walletState()).contains("보류 HELD", "남은 10000");
    }

    @ParameterizedTest
    @EnumSource(value = OrderItemStatus.class, names = {"PURCHASED", "CANCELED", "PAID_OUT"})
    void 구매자_0원이어도_플랫폼_보류와_지급_복구를_재원별로_대조한다(OrderItemStatus status) {
        var target = new LectureHpReconciliationTarget(ITEM_ID, ORDER_ID, status, PRICE, 0L, PRICE, BUYER_ID,
                SELLER_ID, 1L, LocalDateTime.of(2026, 11, 1, 12, 0), false, List.of(),
                List.of(new HoldPromotionDto(42, 901, 3, PRICE)));
        String promotionStatus = status == OrderItemStatus.CANCELED ? "RESTORED"
                : status == OrderItemStatus.PAID_OUT ? "PAID" : "HELD";
        var summary = new HoldStateDto.PromotionAmount(status == OrderItemStatus.PURCHASED ? PRICE : 0L,
                status == OrderItemStatus.PAID_OUT ? PRICE : 0L, status == OrderItemStatus.CANCELED ? PRICE : 0L);
        var settlement = new HoldStateDto.Settlement(SettlementSourceType.LECTURE_ORDER_ITEM, ITEM_ID,
                PayoutType.NORMAL, SELLER_ID, PRICE, PRICE, BillingSettlementStatus.COMPLETED, 0L, PRICE);
        var wallet = new HoldStateDto(BillingReferenceType.LECTURE_ORDER_ITEM, ITEM_ID,
                hold(status == OrderItemStatus.CANCELED ? BillingHoldStatus.RELEASED
                        : status == OrderItemStatus.PAID_OUT ? BillingHoldStatus.PAID : BillingHoldStatus.HELD,
                        BUYER_ID, 0L, 0L, 0L, 0L), null,
                status == OrderItemStatus.PAID_OUT ? List.of(settlement) : List.of(),
                List.of(new HoldStateDto.Promotion(42L, 901L, 3L, "PB-7", PRICE, promotionStatus)), summary);

        assertThat(LectureHpReconciliationComparer.compare(target, wallet)).isEmpty();
        assertThat(rules(LectureHpReconciliationComparer.compare(target, empty())))
                .contains(LectureHpReconciliationRule.HOLD_MISSING);
        var wrongGeneration = new HoldStateDto(wallet.referenceType(), ITEM_ID, wallet.hold(), null,
                wallet.settlements(), List.of(new HoldStateDto.Promotion(42L, 901L, 2L, "PB-7", PRICE, promotionStatus)),
                summary);
        assertThat(rules(LectureHpReconciliationComparer.compare(target, wrongGeneration)))
                .contains(LectureHpReconciliationRule.HOLD_MISMATCH);
    }

    @Test
    void 구매자_0원_결제실패에도_플랫폼_보류가_남으면_불일치다() {
        var target = new LectureHpReconciliationTarget(ITEM_ID, ORDER_ID, OrderItemStatus.HOLD_FAILED, PRICE, 0L,
                PRICE, BUYER_ID, SELLER_ID, 1L, null, false, List.of());
        var wallet = new HoldStateDto(BillingReferenceType.LECTURE_ORDER_ITEM, ITEM_ID, null, null, List.of(),
                List.of(new HoldStateDto.Promotion(42L, 901L, 3L, "PB-7", PRICE, "HELD")),
                new HoldStateDto.PromotionAmount(PRICE, 0L, 0L));
        assertThat(rules(LectureHpReconciliationComparer.compare(target, wallet)))
                .containsExactly(LectureHpReconciliationRule.HOLD_NOT_CLOSED);
        var missingSummary = new HoldStateDto(wallet.referenceType(), ITEM_ID, wallet.hold(), null,
                wallet.settlements(), wallet.promotions(), null);
        assertThat(rules(LectureHpReconciliationComparer.compare(target, missingSummary)))
                .containsExactly(LectureHpReconciliationRule.HOLD_NOT_CLOSED);
    }

    private static LectureHpReconciliationTarget target(OrderItemStatus status) {
        return target(status, PRICE, PRICE);
    }

    private static LectureHpReconciliationTarget target(OrderItemStatus status, long buyerAmount, long sellerPayout) {
        return new LectureHpReconciliationTarget(ITEM_ID, ORDER_ID, status, PRICE, buyerAmount, sellerPayout, BUYER_ID,
                SELLER_ID, 1L, LocalDateTime.of(2026, 11, 1, 12, 0), false, List.of());
    }

    private static HoldStateDto held() {
        return state(hold(BillingHoldStatus.HELD, BUYER_ID, PRICE, PRICE, 0, 0), null, List.of());
    }

    private static HoldStateDto released() {
        return state(hold(BillingHoldStatus.RELEASED, BUYER_ID, PRICE, 0, PRICE, 0), null, List.of());
    }

    private static HoldStateDto paid(HoldStateDto.Settlement... settlements) {
        return state(hold(BillingHoldStatus.PAID, BUYER_ID, PRICE, 0, 0, PRICE), null, List.of(settlements));
    }

    private static HoldStateDto empty() {
        return state(null, null, List.of());
    }

    private static HoldStateDto state(HoldStateDto.Hold hold, HoldStateDto.Voided voided,
                                      List<HoldStateDto.Settlement> settlements) {
        return new HoldStateDto(BillingReferenceType.LECTURE_ORDER_ITEM, ITEM_ID, hold, voided,
                new ArrayList<>(settlements));
    }

    private static HoldStateDto.Hold hold(BillingHoldStatus status, Long payerUserId, long original, long remaining,
                                          long released, long paid) {
        return new HoldStateDto.Hold(status, payerUserId, amount(original), amount(remaining), amount(released),
                amount(paid), amount(0));
    }

    private static HoldStateDto.Amount amount(long totalHp) {
        return new HoldStateDto.Amount(totalHp, 0, totalHp);
    }

    private static HoldStateDto.Settlement settlement(Long receiverUserId, long amountHp,
                                                      BillingSettlementStatus status) {
        return new HoldStateDto.Settlement(SettlementSourceType.LECTURE_ORDER_ITEM, ITEM_ID, PayoutType.NORMAL,
                receiverUserId, amountHp, amountHp, status);
    }

    private static List<LectureHpReconciliationRule> rules(List<LectureHpReconciliationComparer.Finding> findings) {
        return findings.stream().map(LectureHpReconciliationComparer.Finding::rule).toList();
    }
}
