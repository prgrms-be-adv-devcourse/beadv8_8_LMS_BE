package com.hapbang.billing.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.billing.domain.BudgetRelease;

// 쿠폰 예산 해제 기록의 저장·조회를 담당하며 삭제 메서드는 제공하지 않는다.
public interface BudgetReleaseRepository extends Repository<BudgetRelease, Long> {

    // 해제 기록을 저장하고 바로 DB에 반영한다(해제 작업 ID UNIQUE 충돌을 이 자리에서 알기 위해).
    BudgetRelease saveAndFlush(BudgetRelease entity);

    // 해제 작업 ID의 해제 기록을 조회한다(같은 작업의 재요청 확인).
    Optional<BudgetRelease> findByWorkId(String workId);

    // 지갑의 예약마다 해제 기록 금액을 합한다(프로모션 지갑 대사). 해제 기록이 없는 예약은 빠진다.
    @Query("""
            select new com.hapbang.billing.out.BudgetReleaseSum(r.budgetReservationId, sum(r.amountHp))
            from BudgetRelease r, BudgetReservation b
            where b.id = r.budgetReservationId and b.walletId = :walletId
            group by r.budgetReservationId""")
    List<BudgetReleaseSum> sumAmountByReservation(@Param("walletId") Long walletId);
}
