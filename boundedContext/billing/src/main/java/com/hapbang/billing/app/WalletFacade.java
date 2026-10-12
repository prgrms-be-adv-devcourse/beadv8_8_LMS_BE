package com.hapbang.billing.app;

import org.springframework.stereotype.Service;

import com.hapbang.billing.domain.Wallet;

import lombok.RequiredArgsConstructor;

// 가입 이벤트·내 지갑 조회가 쓰는 지갑 모듈 진입점이며, 생성 트랜잭션은 UseCase에 맡겨 여기서는 열지 않는다.
@Service
@RequiredArgsConstructor
public class WalletFacade {

    private final WalletCreateUseCase walletCreateUseCase;

    // 회원 ID로 지갑을 조회하고, 없으면 네 가지 잔액이 0인 지갑을 만들어 반환한다.
    public Wallet getOrCreateWallet(Long userId) {
        return walletCreateUseCase.getOrCreate(userId);
    }
}
