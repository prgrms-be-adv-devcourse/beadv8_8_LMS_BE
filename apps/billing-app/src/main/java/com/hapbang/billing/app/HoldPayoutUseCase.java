package com.hapbang.billing.app;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.app.HoldProcessing.HoldState;
import com.hapbang.billing.app.HoldProcessing.PayoutApplied;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.HoldDetailActionType;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointHold;
import com.hapbang.billing.domain.PointHoldDetail;
import com.hapbang.billing.domain.Settlement;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.domain.BudgetReservation;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.PromotionHold;
import com.hapbang.billing.domain.PromotionHoldStatus;
import com.hapbang.billing.out.BudgetReservationRepository;
import com.hapbang.billing.out.PromotionHoldRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.PayoutType;
import com.hapbang.shared.billing.domain.BillingReferenceType;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

// 참조 대상 보류의 남은 HP 일부 또는 전부를 받는 회원의 수익 HP로 지급한다(정상 지급). 수익 HP 보류를 먼저, 부족분은 충전 HP 보류를
// 최초 배분 순서(FIFO)로 소진하며, 소진한 충전 원금은 보류한 회원에게 돌아가지 않는다. 받는 쪽에는 출처와 관계없이 수익 HP로 들어간다.
// 보류·지급 상세·배분·지급 건·원장·두 지갑 잔액을 한 트랜잭션에서 바꾼다.
@Service
@RequiredArgsConstructor
public class HoldPayoutUseCase {

    private static final String DESCRIPTION = "HP 지급";

    private final HoldProcessing holdProcessing;
    private final LedgerRecordUseCase ledgerRecordUseCase;
    private final BillingEventPublisher billingEventPublisher;
    private final PromotionHoldRepository promotionHoldRepository;
    private final BudgetReservationRepository budgetReservationRepository;
    private final WalletRepository walletRepository;
    private final EntityManager entityManager;

    // 두 지갑 ID 오름차순 락 → 보류 락·소유·지급 원천 확인 → 처리 이력 재생·대조 → (재요청이면 처음 결과) → 지급 배분 계산
    // → 원금 대조 → 배분 소진·보류 감소 → 지급 상세·지급 건 저장 → 원장 기록 순으로 처리한다.
    // 호출한 쪽 트랜잭션이 있으면 그 안에서 실행되며, 어느 단계든 실패하면 모든 변경이 롤백된다.
    @Transactional
    public PayoutResult payout(PaymentPayoutCommand command) {
        BillingCommandValidator.verify(command);
        Long payerWalletId = holdProcessing.walletIdOf(command.payerUserId());
        Long receiverWalletId = holdProcessing.walletIdOf(command.receiverUserId());
        // 같은 지갑 안의 이동은 충전 HP를 수익 HP로 바꾸는 것이 되므로(HP 간 전환 금지) 거절한다.
        if (payerWalletId.equals(receiverWalletId)) {
            throw new BillingException(BillingErrorCode.PAYOUT_TO_PAYER);
        }
        // 생성 중인 보류가 아직 보이지 않아도 관련 지갑을 먼저 ID순으로 잠근 뒤 보류를 찾는다.
        // 그러면 보류 생성이 커밋되기를 기다려 기존과 같이 지급하며, 잠근 뒤 프로모션 지갑 락을 추가하지 않는다.
        List<Long> walletIds = new ArrayList<>(List.of(payerWalletId, receiverWalletId));
        Long promotionWalletId = null;
        if (command.referenceType() == BillingReferenceType.LECTURE_ORDER_ITEM) {
            promotionWalletId = PromotionWalletQueryUseCase.promotionWallet(walletRepository).getId();
            walletIds.add(promotionWalletId);
        }
        Map<Long, Wallet> wallets = ledgerRecordUseCase.lockWallets(walletIds);
        PointHold hold = holdProcessing.lockHold(BillingKeys.hold(command), payerWalletId);
        List<PromotionHold> promotions = promotionHoldRepository.findAllByPointHoldIdOrderByCouponIdAsc(hold.getId());
        HoldProcessing.verifySource(hold, command.sourceType(), command.sourceId());

        String key = BillingKeys.payout(hold.getId(), command);
        HoldState state = holdProcessing.load(hold);
        if (!promotions.isEmpty()) {
            promotions.forEach(entityManager::refresh);
            return payoutPromotion(command, state, promotions, wallets, promotionWalletId, receiverWalletId, key);
        }

        Optional<PointHoldDetail> existing = state.detail(key);
        if (existing.isPresent()) {
            return replay(command, state, existing.get(), receiverWalletId);
        }
        // 지급 상세 없이 원장이나 지급 건만 있는 상태는 정상 재요청이 아니므로 새로 지급하지 않는다.
        if (holdProcessing.ledgerLines(key) > 0 || holdProcessing.settlement(key).isPresent()) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        if (!hold.isOpen() || hold.heldTotalHp() == 0) {
            throw new BillingException(BillingErrorCode.HOLD_ALREADY_CLOSED);
        }
        holdProcessing.rejectSettledSource(hold, command.sourceType(), command.sourceId());
        long amountHp = command.isFull() ? hold.heldTotalHp() : command.amountHp();
        PayoutSplit split = PayoutSplit.of(hold.getHeldRevenueHp(), hold.getHeldChargeHp(), amountHp);
        holdProcessing.verifyChargePrincipal(wallets.get(payerWalletId));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        PayoutApplied applied = holdProcessing.payout(state, split, key);
        Settlement settlement = holdProcessing.saveSettlement(Settlement.completed(command.receiverUserId(),
                receiverWalletId, hold.getId(), command.sourceType(), command.sourceId(), PayoutType.NORMAL,
                split.totalHp(), key, now));
        billingEventPublisher.payoutCompleted(settlement);
        // 두 지갑의 잔액은 record()가 원장으로만 바꾼다(이미 잡은 락을 그대로 쓴다).
        ledgerRecordUseCase.record(new LedgerOperation(key, LedgerTransactionType.PAYOUT, hold.getReferenceType(),
                hold.getReferenceId(), DESCRIPTION, now,
                HoldProcessing.payoutEntries(payerWalletId, receiverWalletId, split)));
        return PayoutResult.of(hold, applied.detail(), settlement, applied.consumptions(), false);
    }

    // 플랫폼 쿠폰이 붙은 주문 항목은 구매자 몫과 플랫폼 몫을 한 번에 전액 지급한다. 0원 앵커에는 상세·구매자 원장을
    // 만들지 않고 지급 건과 프로모션 보류 상태로 결과를 보존한다. 재요청은 지급 건의 재원별 금액을 그대로 돌려준다.
    private PayoutResult payoutPromotion(PaymentPayoutCommand command, HoldState state, List<PromotionHold> promotions,
            Map<Long, Wallet> wallets, Long promotionWalletId, Long receiverWalletId, String key) {
        if (!command.isFull()) {
            throw new BillingException(BillingErrorCode.PROMOTION_PARTIAL_NOT_SUPPORTED);
        }
        PointHold hold = state.hold();
        Optional<Settlement> existing = holdProcessing.settlement(key);
        if (existing.isPresent()) {
            return replayPromotion(command, state, promotions, existing.get(), receiverWalletId);
        }
        if (state.detail(key).isPresent() || holdProcessing.ledgerLines(key) > 0) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        if (!hold.isOpen()) {
            throw new BillingException(BillingErrorCode.HOLD_ALREADY_CLOSED);
        }
        if (promotions.stream().anyMatch(promotion -> promotion.getStatus() != PromotionHoldStatus.HELD)) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        holdProcessing.rejectSettledSource(hold, command.sourceType(), command.sourceId());
        // 지갑 다음에 예약을 쿠폰 ID 오름차순으로 잠근다. 같은 예약을 쓰는 다른 보류·해제와도 직렬화된다.
        List<BudgetReservation> reservations = new ArrayList<>();
        for (PromotionHold promotion : promotions) {
            BudgetReservation reservation = budgetReservationRepository
                    .findByIdForUpdate(promotion.getBudgetReservationId())
                    .orElseThrow(() -> new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT));
            entityManager.refresh(reservation);
            if (!reservation.getWalletId().equals(promotionWalletId)
                    || !reservation.getCouponId().equals(promotion.getCouponId())) {
                throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
            }
            reservations.add(reservation);
        }
        long buyerPaidHp = hold.heldTotalHp();
        long promotionPaidHp = promotionTotal(promotions);
        long paidHp = Math.addExact(buyerPaidHp, promotionPaidHp);
        holdProcessing.verifyChargePrincipal(wallets.get(hold.getWalletId()));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        PayoutSplit split = new PayoutSplit(hold.getHeldRevenueHp(), hold.getHeldChargeHp());
        PayoutApplied applied = null;
        if (buyerPaidHp > 0) {
            applied = holdProcessing.payout(state, split, key);
        } else {
            hold.completePromotionPayout();
        }
        for (int i = 0; i < promotions.size(); i++) {
            reservations.get(i).pay(promotions.get(i).getAmountHp());
            promotions.get(i).pay();
        }
        Settlement settlement = holdProcessing.saveSettlement(Settlement.completed(command.receiverUserId(),
                receiverWalletId, hold.getId(), command.sourceType(), command.sourceId(), PayoutType.NORMAL,
                buyerPaidHp, promotionPaidHp, key, now));
        List<LedgerEntry> entries = new ArrayList<>();
        if (split.revenueHp() > 0) {
            entries.add(LedgerEntry.of(hold.getWalletId(), PointType.REVENUE, BalanceBucket.HELD, -split.revenueHp()));
        }
        if (split.chargeHp() > 0) {
            entries.add(LedgerEntry.of(hold.getWalletId(), PointType.CHARGE, BalanceBucket.HELD, -split.chargeHp()));
        }
        entries.add(LedgerEntry.of(promotionWalletId, PointType.PROMOTION, BalanceBucket.HELD, -promotionPaidHp));
        entries.add(LedgerEntry.of(receiverWalletId, PointType.REVENUE, BalanceBucket.AVAILABLE, paidHp));
        ledgerRecordUseCase.record(new LedgerOperation(key, LedgerTransactionType.PAYOUT, hold.getReferenceType(),
                hold.getReferenceId(), DESCRIPTION, now, entries));
        billingEventPublisher.payoutCompleted(settlement);
        return PayoutResult.of(hold, applied == null ? null : applied.detail(), settlement,
                applied == null ? List.of() : applied.consumptions(), false);
    }

    private PayoutResult replayPromotion(PaymentPayoutCommand command, HoldState state, List<PromotionHold> promotions,
            Settlement settlement, Long receiverWalletId) {
        if (settlement.getPayoutType() != PayoutType.NORMAL
                || !settlement.getReceiverWalletId().equals(receiverWalletId)
                || settlement.getSourceType() != command.sourceType()
                || !settlement.getSourceId().equals(command.sourceId())) {
            throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        PointHoldDetail detail = state.detail(settlement.getIdempotencyKey()).orElse(null);
        long buyerHp = detail == null ? 0L : detail.totalAmountHp();
        int expectedLines = (detail == null ? 0 : HoldProcessing.payoutLines(detail) - 1) + 2;
        if (settlement.getBuyerPaidHp() != buyerHp
                || settlement.getPromotionPaidHp() != promotionTotal(promotions)
                || settlement.getPaidAmountHp() != Math.addExact(buyerHp, settlement.getPromotionPaidHp())
                || promotions.stream().anyMatch(promotion -> promotion.getStatus() != PromotionHoldStatus.PAID)
                || (detail != null && detail.getActionType() != HoldDetailActionType.PAYOUT)
                || (detail == null && state.hold().originalTotalHp() != 0)
                || holdProcessing.ledgerLines(settlement.getIdempotencyKey()) != expectedLines) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        return PayoutResult.of(state.hold(), detail, settlement,
                detail == null ? List.of() : state.history().consumptionsOf(detail.getId()), true);
    }

    private static long promotionTotal(List<PromotionHold> promotions) {
        long sum = 0L;
        for (PromotionHold promotion : promotions) {
            sum = Math.addExact(sum, promotion.getAmountHp());
        }
        return sum;
    }

    // 이미 처리한 같은 요청이면 현재 보류 상태와 관계없이 다시 바꾸지 않고 그때의 지급 결과를 돌려준다.
    // 받는 지갑·지급 원천·(부분 지급이면) 금액이 처음과 다르면 IDEMPOTENCY_KEY_CONFLICT, 지급 건이나 원장 줄이 맞지 않으면
    // HOLD_STATE_INCONSISTENT로 거절한다. 전체 지급 재요청은 금액을 비교하지 않고 처음 지급한 금액을 그대로 돌려준다.
    private PayoutResult replay(PaymentPayoutCommand command, HoldState state, PointHoldDetail detail,
            Long receiverWalletId) {
        Settlement settlement = holdProcessing.settlement(detail.getIdempotencyKey())
                .orElseThrow(() -> new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT));
        if (detail.getActionType() != HoldDetailActionType.PAYOUT
                || settlement.getPayoutType() != PayoutType.NORMAL
                || !settlement.getReceiverWalletId().equals(receiverWalletId)
                || settlement.getSourceType() != command.sourceType()
                || !settlement.getSourceId().equals(command.sourceId())
                || (!command.isFull() && detail.totalAmountHp() != command.amountHp())) {
            throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        if (settlement.getPaidAmountHp() != detail.totalAmountHp()
                || holdProcessing.ledgerLines(detail.getIdempotencyKey()) != HoldProcessing.payoutLines(detail)) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        return PayoutResult.of(state.hold(), detail, settlement, state.history().consumptionsOf(detail.getId()), true);
    }
}
