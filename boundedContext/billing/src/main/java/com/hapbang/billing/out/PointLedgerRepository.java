package com.hapbang.billing.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.PointLedger;

// HP 원장 항목 정보의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다.
public interface PointLedgerRepository extends Repository<PointLedger, Long> {

    // HP 원장 항목 정보를 저장한다.
    PointLedger save(PointLedger entity);

    // 기본키 ID로 HP 원장 항목 정보를 조회한다.
    Optional<PointLedger> findById(Long id);

    // 멱등키로 기존 HP 원장 항목 정보를 조회한다.
    Optional<PointLedger> findByIdempotencyKey(String idempotencyKey);

    // 같은 비즈니스 작업에 속한 원장 항목을 ID 오름차순으로 조회한다.
    List<PointLedger> findAllByOperationKeyOrderByIdAsc(String operationKey);
}
