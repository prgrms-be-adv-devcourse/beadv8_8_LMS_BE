package com.hapbang.billing.app;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.billing.domain.ExchangePayoutStatus;
import com.hapbang.billing.domain.ExchangeStatus;

import lombok.RequiredArgsConstructor;

// 관리자 환전 조회·승인·거절(#294)과 송금 운영자 처리(#304: 송금 시도 조회·결과 재확인·수동 재송금·최종 실패) 진입점.
// 관리자 API는 두 겹으로 막는다: global SecurityConfig의 /api/v1/*/admin/**(ADMIN)과 이 Facade의 @PreAuthorize.
// 트랜잭션은 ExchangeReviewUseCase·ExchangePayoutUseCase(ExchangePayoutProcessing)가 연다.
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ExchangeAdminFacade {

    private final ExchangeReviewUseCase exchangeReviewUseCase;
    private final ExchangePayoutUseCase exchangePayoutUseCase;
    private final ExchangePayoutProcessing exchangePayoutProcessing;

    public Page<AdminExchangeResult> list(ExchangeStatus status, Pageable pageable) {
        return exchangeReviewUseCase.list(status, pageable);
    }

    public AdminExchangeResult get(Long exchangeId) {
        return exchangeReviewUseCase.get(exchangeId);
    }

    public AdminExchangeResult approve(Long adminId, Long exchangeId) {
        return LockTimeouts.translate(() -> exchangeReviewUseCase.approve(adminId, exchangeId));
    }

    public AdminExchangeResult reject(Long adminId, Long exchangeId, String reason) {
        return LockTimeouts.translate(() -> exchangeReviewUseCase.reject(adminId, exchangeId, reason));
    }

    // 상태별 송금 시도(운영자 확인 대상 MANUAL_REVIEW_REQUIRED 등), 오래된 순
    public Page<AdminExchangePayoutResult> payoutAttempts(ExchangePayoutStatus status, Pageable pageable) {
        return exchangePayoutProcessing.attempts(status, pageable);
    }

    // 환전의 송금 시도 이력(시도 번호 순)
    public List<AdminExchangePayoutResult> payoutAttemptsOf(Long exchangeId) {
        return exchangePayoutProcessing.attemptsOf(exchangeId);
    }

    // 결과가 확정되지 않은 마지막 시도를 같은 지급 요청 ID로 다시 확인하고, 처리 후 마지막 시도를 돌려준다
    public AdminExchangePayoutResult recheckPayout(Long adminId, Long exchangeId) {
        LockTimeouts.translate(() -> {
            exchangePayoutUseCase.recheck(adminId, exchangeId);
            return null;
        });
        return latestAttempt(exchangeId);
    }

    // 확정 실패한 환전을 새 시도로 다시 보내고, 처리 후 마지막 시도를 돌려준다
    public AdminExchangePayoutResult retryPayout(Long adminId, Long exchangeId) {
        LockTimeouts.translate(() -> {
            exchangePayoutUseCase.retryFailed(adminId, exchangeId);
            return null;
        });
        return latestAttempt(exchangeId);
    }

    // 확정 실패한 환전을 최종 실패로 종료하고(보류 전액 반환) 환전을 돌려준다
    public AdminExchangeResult failPayoutFinally(Long adminId, Long exchangeId, String reason) {
        LockTimeouts.translate(() -> {
            exchangePayoutUseCase.failFinally(adminId, exchangeId, reason);
            return null;
        });
        return exchangeReviewUseCase.get(exchangeId);
    }

    private AdminExchangePayoutResult latestAttempt(Long exchangeId) {
        return exchangePayoutProcessing.attemptsOf(exchangeId).getLast();
    }
}
