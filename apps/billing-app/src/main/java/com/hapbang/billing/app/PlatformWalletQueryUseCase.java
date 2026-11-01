package com.hapbang.billing.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.domain.WalletType;
import com.hapbang.billing.out.PointLedgerRepository;
import com.hapbang.billing.out.WalletRepository;

import lombok.RequiredArgsConstructor;

// 플랫폼 지갑의 수수료 수입 잔액·내역 조회 (#336). 읽기만 한다. 권한은 PlatformWalletAdminFacade가 막는다.
// 플랫폼 지갑은 Flyway 마이그레이션이 만들므로 없으면 배포가 잘못된 것이라 예외(500)다.
@Service
@RequiredArgsConstructor
public class PlatformWalletQueryUseCase {

    private final WalletRepository walletRepository;
    private final PointLedgerRepository pointLedgerRepository;

    @Transactional(readOnly = true)
    public PlatformWalletResult get() {
        Wallet wallet = platformWallet();
        return new PlatformWalletResult(wallet.getId(), wallet.getFeeBalanceHp());
    }

    // 환전 수수료 수입을 기록 시각 최신순으로 돌려준다. 정렬은 고정이라 요청의 정렬은 받지 않는다.
    @Transactional(readOnly = true)
    public Page<FeeIncomeResult> feeIncomes(Pageable pageable) {
        PageRequest page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return pointLedgerRepository.findExchangeFeeIncomes(platformWallet().getId(), page)
                .map(FeeIncomeResult::from);
    }

    private Wallet platformWallet() {
        return walletRepository.findByWalletType(WalletType.PLATFORM)
                .orElseThrow(() -> new IllegalStateException("플랫폼 지갑이 없습니다."));
    }
}
