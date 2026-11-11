package com.hapbang.billing.out;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.billing.domain.PromotionHold;
import com.hapbang.billing.domain.PromotionHoldStatus;

// 프로모션 보류의 저장·조회를 담당하며 삭제 메서드는 제공하지 않는다.
public interface PromotionHoldRepository extends Repository<PromotionHold, Long> {

    // 프로모션 보류를 저장하고 바로 DB에 반영한다(보류 × 쿠폰 UNIQUE 충돌을 이 자리에서 알기 위해).
    PromotionHold saveAndFlush(PromotionHold entity);

    // 구매자 보류 하나에 붙은 프로모션 보류를 쿠폰 ID 오름차순으로 조회한다.
    List<PromotionHold> findAllByPointHoldIdOrderByCouponIdAsc(Long pointHoldId);

    List<PromotionHold> findAllByPointHoldIdInOrderByCouponIdAsc(List<Long> pointHoldIds);

    // 지갑의 예약마다 한 상태의 프로모션 보류 금액을 합한다(프로모션 지갑 대사). 그 상태의 보류가 없는 예약은 빠진다.
    @Query("""
            select new com.hapbang.billing.out.PromotionHoldSum(h.budgetReservationId, sum(h.amountHp))
            from PromotionHold h, BudgetReservation b
            where b.id = h.budgetReservationId and b.walletId = :walletId and h.status = :status
            group by h.budgetReservationId""")
    List<PromotionHoldSum> sumAmountByReservation(@Param("walletId") Long walletId,
            @Param("status") PromotionHoldStatus status);
}
