package com.hapbang.billing.out;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.BudgetReservation;

// 쿠폰 예산 예약의 저장·조회를 담당하며 삭제 메서드는 제공하지 않는다.
public interface BudgetReservationRepository extends Repository<BudgetReservation, Long> {

    // 예약을 저장하고 바로 DB에 반영한다(쿠폰·예약 작업 ID UNIQUE 충돌을 이 자리에서 알기 위해).
    BudgetReservation saveAndFlush(BudgetReservation entity);

    Optional<BudgetReservation> findById(Long id);

    // 예약 작업 ID의 예약을 조회한다(같은 작업의 재요청 확인).
    Optional<BudgetReservation> findByWorkId(String workId);

    // 쿠폰의 예약을 조회한다. 쿠폰당 예약은 하나다.
    Optional<BudgetReservation> findByCouponId(Long couponId);
}
