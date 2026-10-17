package com.hapbang.billing.app;

import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.billing.domain.Wallet;

import lombok.RequiredArgsConstructor;

// 가입 이벤트·내 지갑 조회가 쓰는 지갑 모듈 진입점이며, 생성 트랜잭션은 UseCase에 맡겨 여기서는 열지 않는다.
@Service
@RequiredArgsConstructor
public class WalletFacade {

    private final WalletCreateUseCase walletCreateUseCase;
    private final WalletTransactionQueryUseCase walletTransactionQueryUseCase;

    // 로그인한 회원의 지갑을 조회하고, 없으면 만들어 반환한다.
    // 관리자는 HP를 쓰지 않으므로 지갑을 만들기 전에 역할 검사(@PreAuthorize)로 거절한다 (2026-10-12 회의).
    @PreAuthorize("hasAnyRole('USER', 'INFLUENCER', 'VERIFIED_INFLUENCER')")
    public Wallet getMyWallet(Long userId) {
        return walletCreateUseCase.getOrCreate(userId);
    }

    // 로그인한 회원의 HP 거래 내역을 작업 단위 최신순으로 조회한다 (#295). 역할은 내 지갑 조회와 같다(관리자 403).
    @PreAuthorize("hasAnyRole('USER', 'INFLUENCER', 'VERIFIED_INFLUENCER')")
    public Page<WalletTransactionResult> getMyTransactions(Long userId, int page, int size) {
        return walletTransactionQueryUseCase.list(userId, page, size);
    }

    // 회원 ID로 지갑을 조회하고, 없으면 네 가지 잔액이 0인 지갑을 만들어 반환한다.
    // 가입 이벤트 리스너는 로그인 정보 없이 호출하므로 역할 검사를 두지 않는다 (관리자 제외는 리스너가 한다).
    public Wallet getOrCreateWallet(Long userId) {
        return walletCreateUseCase.getOrCreate(userId);
    }
}
