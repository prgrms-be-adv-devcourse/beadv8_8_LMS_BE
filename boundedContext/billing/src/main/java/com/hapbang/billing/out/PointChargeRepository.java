package com.hapbang.billing.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.billing.domain.PointCharge;
import com.hapbang.billing.domain.PointChargeStatus;

import jakarta.persistence.LockModeType;

// 충전 건 정보의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다.
public interface PointChargeRepository extends Repository<PointCharge, Long> {

    // 충전 건 정보를 저장한다.
    PointCharge save(PointCharge entity);

    // 충전 건을 저장하고 바로 flush한다. Repository를 거쳐야 UNIQUE 위반이 DataIntegrityViolationException으로 바뀐다 (#224).
    PointCharge saveAndFlush(PointCharge entity);

    // 결과를 확정하지 못한 충전 건의 확인 시각을 남긴다(사유가 같아 엔티티 변경이 없어도). 결과 확인 배치가 오래된 순으로
    // 가져가므로, 확인한 건이 뒤로 가야 같은 미확정 건만 반복하지 않는다 (#224).
    @Modifying(flushAutomatically = true)
    @Query("update PointCharge c set c.updatedAt = :checkedAt where c.id = :id")
    int touchUpdatedAt(@Param("id") Long id, @Param("checkedAt") Instant checkedAt);

    // 기본키 ID로 충전 건 정보를 조회한다.
    Optional<PointCharge> findById(Long id);

    // PG 주문 ID에 해당하는 충전 건을 조회한다.
    Optional<PointCharge> findByPgOrderId(String pgOrderId);

    // PG 주문 ID로 충전 건을 조회하며 트랜잭션이 끝날 때까지 쓰기 락을 건다. 지갑 락 없이 승인 요청을 시작할 때만 쓴다 (#224).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PointCharge c where c.pgOrderId = :pgOrderId")
    Optional<PointCharge> findByPgOrderIdForUpdate(@Param("pgOrderId") String pgOrderId);

    // 충전 건을 ID로 조회하며 쓰기 락을 건다. 충전 확정은 지갑 락을 먼저 잡은 뒤 호출한다(지갑 → 충전 건) (#224).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PointCharge c where c.id = :id")
    Optional<PointCharge> findByIdForUpdate(@Param("id") Long id);

    // 같은 결제키(HMAC)를 쓴 충전 건이 있는지 확인한다. 최종 방어는 uk_point_charge_pg_payment_key_hash다 (#224).
    boolean existsByPgPaymentKeyHash(String pgPaymentKeyHash);

    // 결과 확인 대상: 지정 상태이고 마지막 변경이 기준 시각 이전인 충전 건 ID를 오래된 순으로 조회한다 (#224).
    @Query("""
            select c.id from PointCharge c
            where c.status = :status and c.updatedAt < :before
            order by c.updatedAt asc, c.id asc""")
    List<Long> findIdsByStatusUpdatedBefore(@Param("status") PointChargeStatus status,
            @Param("before") Instant before, Pageable pageable);

    // 지갑의 배분 가능한 충전 건(지정 상태·가용 원금 > 0)을 FIFO 순서(완료 시각, ID 오름차순)로 조회하며 트랜잭션이 끝날 때까지 쓰기 락을 건다.
    // 지갑 락을 먼저 잡은 뒤에만 호출해 지갑 → 충전 건 락 순서를 지킨다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from PointCharge c
            where c.walletId = :walletId
              and c.status in :statuses
              and c.remainingPrincipalHp > 0
            order by c.completedAt asc, c.id asc""")
    List<PointCharge> findAllocatableForUpdate(@Param("walletId") Long walletId,
            @Param("statuses") Collection<PointChargeStatus> statuses);

    // 지정한 충전 건들을 ID 오름차순으로 조회하며 트랜잭션이 끝날 때까지 쓰기 락을 건다.
    // 지갑 락을 먼저 잡은 뒤에만 호출해 지갑 → 충전 건 락 순서를 지킨다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PointCharge c where c.id in :ids order by c.id asc")
    List<PointCharge> findAllByIdInForUpdate(@Param("ids") Collection<Long> ids);

    // 지갑의 지정 상태 충전 건 가용 원금 합계를 구하며, 없으면 0이다.
    @Query("""
            select coalesce(sum(c.remainingPrincipalHp), 0) from PointCharge c
            where c.walletId = :walletId and c.status in :statuses""")
    long sumRemainingPrincipal(@Param("walletId") Long walletId,
            @Param("statuses") Collection<PointChargeStatus> statuses);
}
