package com.hapbang.billing.app;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BudgetReservation;
import com.hapbang.billing.domain.PointHold;
import com.hapbang.billing.domain.PromotionHold;
import com.hapbang.billing.domain.PromotionHoldStatus;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.BudgetReservationRepository;
import com.hapbang.billing.out.PromotionHoldRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

// 반환·무효화가 함께 쓰는 프로모션 예산 복구. 호출자가 연 트랜잭션 안에서만 실행한다.
@Component
@RequiredArgsConstructor
class PromotionReleaseProcessing {
    private final WalletRepository walletRepository;
    private final BudgetReservationRepository budgetReservationRepository;
    private final PromotionHoldRepository promotionHoldRepository;
    private final LedgerRecordUseCase ledgerRecordUseCase;
    private final HoldProcessing holdProcessing;
    private final EntityManager entityManager;
    private final HoldWalletLocking holdWalletLocking;

    // 지갑 집합은 보류의 불변 재원 구성으로 먼저 정하고, 보류 상태는 지갑 락 뒤에 다시 읽는다.
    Wallet lockWallets(Long buyerWalletId, BillingReferenceType referenceType, Long referenceId) {
        return holdWalletLocking.lockWallets(List.of(buyerWalletId), referenceType, referenceId).get(buyerWalletId);
    }

    List<PromotionHold> promotions(PointHold hold) {
        List<PromotionHold> promotions = promotionHoldRepository.findAllByPointHoldIdOrderByCouponIdAsc(hold.getId());
        promotions.forEach(entityManager::refresh);
        return promotions;
    }

    List<ReleaseResult.PromotionRestoration> restore(List<PromotionHold> promotions, String requestId, Instant now) {
        Long walletId = PromotionWalletQueryUseCase.promotionWallet(walletRepository).getId();
        for (PromotionHold promotion : promotions) {
            if (promotion.getStatus() != PromotionHoldStatus.HELD) {
                throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
            }
            BudgetReservation reservation = budgetReservationRepository
                    .findByIdForUpdate(promotion.getBudgetReservationId())
                    .orElseThrow(() -> new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT));
            entityManager.refresh(reservation);
            if (!reservation.getCouponId().equals(promotion.getCouponId())
                    || !reservation.getWalletId().equals(walletId)) {
                throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
            }
            reservation.restore(promotion.getAmountHp());
            promotion.restore(requestId);
            ledgerRecordUseCase.record(PromotionLedgerOperations.promotionRestore(walletId, promotion, now));
        }
        return restorations(promotions);
    }

    // 이미 복구한 목록은 최초 요청 ID와 원장을 대조한 뒤 재생한다. 다른 요청으로 다시 복구하지 않는다.
    List<ReleaseResult.PromotionRestoration> replay(List<PromotionHold> promotions, String requestId) {
        for (PromotionHold promotion : promotions) {
            if (promotion.getStatus() != PromotionHoldStatus.RESTORED
                    || !requestId.equals(promotion.getRestorationRequestId())
                    || holdProcessing.ledgerLines(BillingKeys.promotionRestore(promotion.getId())) != 2) {
                throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
            }
        }
        return restorations(promotions);
    }

    static List<ReleaseResult.PromotionRestoration> restorations(List<PromotionHold> promotions) {
        return promotions.stream().map(promotion -> new ReleaseResult.PromotionRestoration(promotion.getCouponId(),
                promotion.getMemberCouponId(), promotion.getGeneration(), promotion.getAmountHp())).toList();
    }
}
