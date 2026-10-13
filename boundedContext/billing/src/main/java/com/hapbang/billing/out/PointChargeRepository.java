package com.hapbang.billing.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
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

    // 기본키 ID로 충전 건 정보를 조회한다.
    Optional<PointCharge> findById(Long id);

    // PG 주문 ID에 해당하는 충전 건을 조회한다.
    Optional<PointCharge> findByPgOrderId(String pgOrderId);

    // 지갑의 배분 가능한 충전 건(지정 상태·가용 원금 > 0)을 FIFO 순서(완료 시각, ID 오름차순)로 조회하며 트랜잭션이 끝날 때까지 쓰기 락을 건다.
    // 지갑 락을 먼저 잡은 뒤에만 호출해 지갑 → 충전 건 락 순서를 지킨다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from PointCharge c
            where c.wallet.id = :walletId
              and c.status in :statuses
              and c.remainingPrincipalHp > 0
            order by c.completedAt asc, c.id asc""")
    List<PointCharge> findAllocatableForUpdate(@Param("walletId") Long walletId,
            @Param("statuses") Collection<PointChargeStatus> statuses);
}
