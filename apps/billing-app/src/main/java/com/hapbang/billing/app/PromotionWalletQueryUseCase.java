package com.hapbang.billing.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.domain.WalletType;
import com.hapbang.billing.out.WalletRepository;

import lombok.RequiredArgsConstructor;

// 프로모션 지갑 칸별 잔액 조회. 읽기만 한다. 권한은 PromotionWalletAdminFacade가 막는다.
// 프로모션 지갑은 Flyway 마이그레이션이 만들므로 없으면 배포가 잘못된 것이라 예외(500)다.
@Service
@RequiredArgsConstructor
public class PromotionWalletQueryUseCase {

    private final WalletRepository walletRepository;

    @Transactional(readOnly = true)
    public PromotionWalletResult get() {
        return resultOf(promotionWallet(walletRepository));
    }

    static PromotionWalletResult resultOf(Wallet wallet) {
        return new PromotionWalletResult(wallet.getId(),
                wallet.balanceOf(PointType.PROMOTION, BalanceBucket.AVAILABLE),
                wallet.balanceOf(PointType.PROMOTION, BalanceBucket.RESERVED),
                wallet.balanceOf(PointType.PROMOTION, BalanceBucket.HELD));
    }

    static Wallet promotionWallet(WalletRepository walletRepository) {
        return walletRepository.findByWalletType(WalletType.PROMOTION)
                .orElseThrow(() -> new IllegalStateException("프로모션 지갑이 없습니다."));
    }
}
