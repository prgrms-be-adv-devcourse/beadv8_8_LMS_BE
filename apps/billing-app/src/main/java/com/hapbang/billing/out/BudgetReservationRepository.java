package com.hapbang.billing.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.billing.domain.BudgetReservation;

import jakarta.persistence.LockModeType;

// 쿠폰 예산 예약의 저장·조회를 담당하며 삭제 메서드는 제공하지 않는다.
public interface BudgetReservationRepository extends Repository<BudgetReservation, Long> {

    // 예약을 저장하고 바로 DB에 반영한다(쿠폰·예약 작업 ID UNIQUE 충돌을 이 자리에서 알기 위해).
    BudgetReservation saveAndFlush(BudgetReservation entity);

    Optional<BudgetReservation> findById(Long id);

    // 해제·보류로 남은 예산을 바꾸기 위해 예약을 조회하며 트랜잭션이 끝날 때까지 행에 쓰기 락을 건다. 프로모션 지갑 락 다음에 잡는다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BudgetReservation r where r.id = :id")
    Optional<BudgetReservation> findByIdForUpdate(@Param("id") Long id);

    // 예약 작업 ID의 예약을 조회한다(같은 작업의 재요청 확인).
    Optional<BudgetReservation> findByWorkId(String workId);

    // 주문 항목 보류로 남은 예산을 바꾸기 위해 쿠폰의 예약을 쓰기 락으로 조회한다. 관련 지갑 락 다음에 쿠폰 ID 오름차순으로 잡는다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BudgetReservation r where r.couponId = :couponId")
    Optional<BudgetReservation> findByCouponIdForUpdate(@Param("couponId") Long couponId);

    // 지갑의 예약 전부(프로모션 지갑 대사)
    List<BudgetReservation> findAllByWalletId(Long walletId);

    // 쿠폰의 예약을 조회한다. 쿠폰당 예약은 하나다.
    Optional<BudgetReservation> findByCouponId(Long couponId);
}
