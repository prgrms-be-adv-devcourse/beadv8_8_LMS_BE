package com.hapbang.billing.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

// 회원 탈퇴용 지갑 닫기·다시 열기의 진입점 (#623, docs/api/internal-withdrawals.md 4장). 트랜잭션은 UseCase가 연다.
// 회원 앱이 내부 HTTP API로 부르므로 내부 호출(ROLE_INTERNAL)만 허용한다(HoldFacade와 같다). 락 대기 초과는 HP_LOCK_TIMEOUT(503)이다.
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('INTERNAL')")
public class WalletWithdrawalFacade {

    private final WalletWithdrawalUseCase walletWithdrawalUseCase;

    public WalletWithdrawalCloseResult close(long withdrawalId, long userId) {
        return LockTimeouts.translate(() -> walletWithdrawalUseCase.close(withdrawalId, userId));
    }

    public WalletWithdrawalReopenResult reopen(long withdrawalId, long userId) {
        return LockTimeouts.translate(() -> walletWithdrawalUseCase.reopen(withdrawalId, userId));
    }
}
