package com.hapbang.billing.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

// 관리자 플랫폼 지갑 조회 진입점 (#336). 관리자 API는 두 겹으로 막는다: global SecurityConfig의 /api/v1/*/admin/**(ADMIN)과
// 이 Facade의 @PreAuthorize. 플랫폼 지갑은 이 조회와 환전 송금 성공 처리(수수료 수입 기록) 말고는 쓰지 않는다.
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PlatformWalletAdminFacade {

    private final PlatformWalletQueryUseCase platformWalletQueryUseCase;

    public PlatformWalletResult get() {
        return platformWalletQueryUseCase.get();
    }

    public Page<FeeIncomeResult> feeIncomes(Pageable pageable) {
        return platformWalletQueryUseCase.feeIncomes(pageable);
    }
}
