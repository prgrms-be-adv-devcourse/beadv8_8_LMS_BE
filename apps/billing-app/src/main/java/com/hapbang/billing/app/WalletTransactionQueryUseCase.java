package com.hapbang.billing.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.out.PointLedgerRepository;
import com.hapbang.billing.out.WalletRepository;

import lombok.RequiredArgsConstructor;

// 내 HP 거래 내역 조회 (#295). 원장을 지갑 + 작업 키로 묶어 DB에서 합하고 작업 단위로 페이지를 나눈다. 원장은 읽기만 한다.
@Service
@RequiredArgsConstructor
public class WalletTransactionQueryUseCase {

    private final WalletRepository walletRepository;
    private final PointLedgerRepository pointLedgerRepository;

    // 회원 지갑의 거래를 최신 작업(첫 원장 ID 내림차순)부터 돌려준다. 정렬은 고정이라 요청의 정렬은 받지 않는다.
    // 지갑이 없으면 거래도 없으므로 지갑을 만들지 않고 빈 페이지를 돌려준다.
    @Transactional(readOnly = true)
    public Page<WalletTransactionResult> list(Long userId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        return walletRepository.findByUserId(userId)
                .map(wallet -> pointLedgerRepository.findOperationSumsByWalletId(wallet.getId(), pageable)
                        .map(WalletTransactionResult::from))
                .orElseGet(() -> Page.empty(pageable));
    }
}
