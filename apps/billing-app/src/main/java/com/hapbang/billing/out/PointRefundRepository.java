package com.hapbang.billing.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.billing.domain.PointRefund;

// 개별 충전 건의 환불 정보의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다.
public interface PointRefundRepository extends Repository<PointRefund, Long> {

    // 개별 충전 건의 환불 정보를 저장한다.
    PointRefund save(PointRefund entity);

    // 기본키 ID로 개별 충전 건의 환불 정보를 조회한다.
    Optional<PointRefund> findById(Long id);

    // 멱등키로 기존 개별 충전 건의 환불 정보를 조회한다.
    Optional<PointRefund> findByIdempotencyKey(String idempotencyKey);

    // 하나의 환불 요청에 속한 개별 환불 내역을 ID 오름차순으로 조회한다. 환불 요청을 만들 때 FIFO 배분 순서로 저장하므로
    // 이 순서가 PG 취소 순서다 (#260).
    List<PointRefund> findAllByRefundRequestIdOrderByIdAsc(Long refundRequestId);

    // 개별 환불의 마지막 변경 시각을 남긴다(결과 확인 중 같은 상태로 남을 때, #260).
    @Modifying(flushAutomatically = true)
    @Query("update PointRefund r set r.updatedAt = :touchedAt where r.id = :id")
    int touchUpdatedAt(@Param("id") Long id, @Param("touchedAt") Instant touchedAt);
}
