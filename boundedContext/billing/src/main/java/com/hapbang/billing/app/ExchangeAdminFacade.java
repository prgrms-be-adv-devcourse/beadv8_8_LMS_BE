package com.hapbang.billing.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.billing.domain.ExchangeStatus;

import lombok.RequiredArgsConstructor;

// 관리자 환전 조회·승인·거절 진입점 (#294). 관리자 API는 두 겹으로 막는다: global SecurityConfig의 /api/v1/*/admin/**(ADMIN)과
// 이 Facade의 @PreAuthorize. 트랜잭션은 ExchangeReviewUseCase가 연다. 실제 송금·확정 차감은 하지 않는다(후속 Issue).
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ExchangeAdminFacade {

    private final ExchangeReviewUseCase exchangeReviewUseCase;

    public Page<AdminExchangeResult> list(ExchangeStatus status, Pageable pageable) {
        return exchangeReviewUseCase.list(status, pageable);
    }

    public AdminExchangeResult get(Long exchangeId) {
        return exchangeReviewUseCase.get(exchangeId);
    }

    public AdminExchangeResult approve(Long adminId, Long exchangeId) {
        return exchangeReviewUseCase.approve(adminId, exchangeId);
    }

    public AdminExchangeResult reject(Long adminId, Long exchangeId, String reason) {
        return exchangeReviewUseCase.reject(adminId, exchangeId, reason);
    }
}
