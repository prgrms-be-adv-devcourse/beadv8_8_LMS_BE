package com.hapbang.billing.out;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.Settlement;

// 수익 HP 지급 정보의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다.
public interface SettlementRepository extends Repository<Settlement, Long> {

    // 수익 HP 지급 정보를 저장한다.
    Settlement save(Settlement entity);

    // 기본키 ID로 수익 HP 지급 정보를 조회한다.
    Optional<Settlement> findById(Long id);

    // 멱등키로 기존 수익 HP 지급 정보를 조회한다.
    Optional<Settlement> findByIdempotencyKey(String idempotencyKey);
}
