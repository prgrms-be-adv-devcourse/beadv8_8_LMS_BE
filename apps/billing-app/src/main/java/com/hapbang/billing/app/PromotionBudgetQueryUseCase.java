package com.hapbang.billing.app;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BudgetRelease;
import com.hapbang.billing.domain.BudgetReservation;
import com.hapbang.billing.out.BudgetReleaseRepository;
import com.hapbang.billing.out.BudgetReservationRepository;
import com.hapbang.shared.billing.domain.PromotionBudgetWorkKind;

import lombok.RequiredArgsConstructor;

// 쿠폰 예산 작업·예약 조회. 읽기만 하고 락을 잡지 않는다. 작업 조회는 응답을 잃은 호출한 쪽이 결과를 확인할 때 쓴다.
@Service
@RequiredArgsConstructor
public class PromotionBudgetQueryUseCase {

    private final BudgetReservationRepository budgetReservationRepository;
    private final BudgetReleaseRepository budgetReleaseRepository;

    // 작업 ID로 예약·해제 기록을 찾는다. 거절한 작업은 저장하지 않으므로 기록이 없으면 NOT_FOUND다(같은 작업 ID로 다시 보내면 된다).
    @Transactional(readOnly = true)
    public BudgetWorkResult work(String workId) {
        Optional<BudgetReservation> reservation = budgetReservationRepository.findByWorkId(workId);
        if (reservation.isPresent()) {
            return BudgetWorkResult.done(workId, PromotionBudgetWorkKind.RESERVE, reservation.get().getId(),
                    reservation.get().getAmountHp());
        }
        Optional<BudgetRelease> release = budgetReleaseRepository.findByWorkId(workId);
        return release.map(found -> BudgetWorkResult.done(workId, PromotionBudgetWorkKind.RELEASE,
                found.getBudgetReservationId(), found.getAmountHp())).orElseGet(() -> BudgetWorkResult.notFound(workId));
    }

    // 예약의 총액과 남은·보류·지급·해제 예산. 없으면 BUDGET_RESERVATION_NOT_FOUND(404)다.
    @Transactional(readOnly = true)
    public BudgetReservationResult reservation(long reservationId) {
        return budgetReservationRepository.findById(reservationId).map(BudgetReservationResult::from)
                .orElseThrow(() -> new BillingException(BillingErrorCode.BUDGET_RESERVATION_NOT_FOUND));
    }
}
