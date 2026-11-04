package com.hapbang.billing.out;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.PointHold;

// HP 보류 정보의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다.
public interface PointHoldRepository extends Repository<PointHold, Long> {

    // HP 보류 정보를 저장한다.
    PointHold save(PointHold entity);

    // 기본키 ID로 HP 보류 정보를 조회한다.
    Optional<PointHold> findById(Long id);

    // 멱등키로 기존 HP 보류 정보를 조회한다.
    Optional<PointHold> findByIdempotencyKey(String idempotencyKey);
}
