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

import com.hapbang.billing.domain.PointRefundRequest;
import com.hapbang.billing.domain.PointRefundRequestStatus;

import jakarta.persistence.LockModeType;

// 환불 요청 정보의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다.
public interface PointRefundRequestRepository extends Repository<PointRefundRequest, Long> {

    // 환불 요청 정보를 저장한다.
    PointRefundRequest save(PointRefundRequest entity);

    // 기본키 ID로 환불 요청 정보를 조회한다.
    Optional<PointRefundRequest> findById(Long id);

    // 멱등키로 기존 환불 요청 정보를 조회한다.
    Optional<PointRefundRequest> findByIdempotencyKey(String idempotencyKey);

    // 환불 요청을 ID로 조회하며 쓰기 락을 건다 (#260). 지갑 락을 잡는 단계는 지갑 → 환불 요청 → 보류 → 충전 건 순서로 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PointRefundRequest r where r.id = :id")
    Optional<PointRefundRequest> findByIdForUpdate(@Param("id") Long id);

    // 환불 요청의 마지막 변경 시각을 남긴다(엔티티 변경이 없어도). 결과 확인 배치가 오래된 순으로 가져가므로, 확인한 요청이 뒤로 가야
    // 같은 미확정 요청만 반복하지 않는다 (#260).
    @Modifying(flushAutomatically = true)
    @Query("update PointRefundRequest r set r.updatedAt = :touchedAt where r.id = :id")
    int touchUpdatedAt(@Param("id") Long id, @Param("touchedAt") Instant touchedAt);

    // 결과 확인 대상: 지정 상태이고 마지막 변경이 기준 시각 이전인 환불 요청 ID를 오래된 순으로 조회한다 (#260).
    @Query("""
            select r.id from PointRefundRequest r
            where r.status in :statuses and r.updatedAt < :before
            order by r.updatedAt asc, r.id asc""")
    List<Long> findIdsByStatusInUpdatedBefore(@Param("statuses") Collection<PointRefundRequestStatus> statuses,
            @Param("before") Instant before, Pageable pageable);
}
