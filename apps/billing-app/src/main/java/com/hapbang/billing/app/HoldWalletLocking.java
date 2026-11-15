package com.hapbang.billing.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.hapbang.billing.domain.PointHold;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.PointHoldRepository;
import com.hapbang.billing.out.PromotionHoldRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;

import lombok.RequiredArgsConstructor;

// 보류 처리에 필요한 지갑 집합을 먼저 정하고 ID 오름차순으로 잠근다. 호출자가 연 트랜잭션 안에서 쓴다.
// 보류와 프로모션 행은 함께 커밋되고 이후 구성은 바뀌지 않으므로, 이미 보인 비프로모션 보류는 회원 지갑만 잠가도 된다.
// 보류가 아직 안 보이면 생성 중일 수 있어 프로모션 지갑까지 미리 포함한다. 여기서는 미존재를 오류로 판정하지 않는다.
// 보류 상태·소유·금액은 호출자가 지갑 락을 얻은 다음 보류를 잠그고 refresh한 최신 값으로 확인한다.
@Component
@RequiredArgsConstructor
class HoldWalletLocking {

    private final PointHoldRepository pointHoldRepository;
    private final PromotionHoldRepository promotionHoldRepository;
    private final WalletRepository walletRepository;
    private final LedgerRecordUseCase ledgerRecordUseCase;

    Map<Long, Wallet> lockWallets(List<Long> participants, BillingReferenceType referenceType, Long referenceId) {
        List<Long> walletIds = new ArrayList<>(participants);
        if (requiresPromotionWallet(referenceType, referenceId)) {
            walletIds.add(PromotionWalletQueryUseCase.promotionWallet(walletRepository).getId());
        }
        return ledgerRecordUseCase.lockWallets(walletIds);
    }

    private boolean requiresPromotionWallet(BillingReferenceType referenceType, Long referenceId) {
        if (referenceType != BillingReferenceType.LECTURE_ORDER_ITEM) {
            return false;
        }
        return pointHoldRepository.findByIdempotencyKey(BillingKeys.hold(referenceType, referenceId))
                .map(this::hasPromotions)
                .orElse(true);
    }

    private boolean hasPromotions(PointHold hold) {
        return !promotionHoldRepository.findAllByPointHoldIdOrderByCouponIdAsc(hold.getId()).isEmpty();
    }
}
