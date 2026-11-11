package com.hapbang.billing.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.billing.domain.PointHold;
import com.hapbang.shared.billing.domain.BillingReferenceType;

// HP 보류 정보의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다.
public interface PointHoldRepository extends Repository<PointHold, Long> {

    // HP 보류 정보를 저장한다.
    PointHold save(PointHold entity);

    // 기본키 ID로 HP 보류 정보를 조회한다.
    Optional<PointHold> findById(Long id);

    // 멱등키로 기존 HP 보류 정보를 조회한다.
    Optional<PointHold> findByIdempotencyKey(String idempotencyKey);

    // 서비스 간 HP 대사 조회 (#577). 참조 대상의 보류 멱등키(HOLD:{유형}:{ID}, UNIQUE)로 찾는다
    List<PointHold> findAllByIdempotencyKeyIn(Collection<String> idempotencyKeys);

    // 참조 유형별로 (updatedAfter, afterId) 뒤에 바뀐 보류를 마지막 변경 시각·ID 순으로 읽는다(키셋, idx_point_hold_2, #577).
    // updatedBefore보다 최근에 바뀐 보류는 아직 커밋 전일 수 있어 읽지 않는다(HoldQueryUseCase의 확정 대기 시간)
    @Query("""
            select h from PointHold h
            where h.referenceType = :referenceType
              and (h.updatedAt > :updatedAfter or (h.updatedAt = :updatedAfter and h.id > :afterId))
              and h.updatedAt < :updatedBefore
            order by h.updatedAt, h.id""")
    List<PointHold> findChangedAfter(@Param("referenceType") BillingReferenceType referenceType,
                                     @Param("updatedAfter") Instant updatedAfter, @Param("afterId") long afterId,
                                     @Param("updatedBefore") Instant updatedBefore, Limit limit);
}
