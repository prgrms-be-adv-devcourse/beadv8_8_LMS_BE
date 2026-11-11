package com.hapbang.billing.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

// 쿠폰 예산 예약·해제·조회의 Billing 진입점. 쿠폰이 내부 HTTP API(in.ApiV1BillingPromotionBudgetInternalController)로 부르는
// 내부 기능이라 내부 호출(ROLE_INTERNAL)만 허용한다. 트랜잭션은 UseCase가 열고, 락 대기 초과는 HP_LOCK_TIMEOUT(503)으로 바꾼다.
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('INTERNAL')")
public class PromotionBudgetFacade {

    private final PromotionBudgetUseCase promotionBudgetUseCase;
    private final PromotionBudgetQueryUseCase promotionBudgetQueryUseCase;

    public BudgetReserveResult reserve(BudgetReserveCommand command) {
        return LockTimeouts.translate(() -> promotionBudgetUseCase.reserve(command));
    }

    public BudgetReleaseResult release(BudgetReleaseCommand command) {
        return LockTimeouts.translate(() -> promotionBudgetUseCase.release(command));
    }

    public BudgetWorkResult work(String workId) {
        return promotionBudgetQueryUseCase.work(workId);
    }

    public BudgetReservationResult reservation(long reservationId) {
        return promotionBudgetQueryUseCase.reservation(reservationId);
    }
}
