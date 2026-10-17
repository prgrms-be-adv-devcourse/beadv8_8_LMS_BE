package com.hapbang.billing.out;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.Settlement;
import com.hapbang.shared.billing.domain.SettlementSourceType;

// 수익 HP 지급 정보의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다.
public interface SettlementRepository extends Repository<Settlement, Long> {

    // 수익 HP 지급 정보를 저장한다.
    Settlement save(Settlement entity);

    // 기본키 ID로 수익 HP 지급 정보를 조회한다.
    Optional<Settlement> findById(Long id);

    // 멱등키로 기존 수익 HP 지급 정보를 조회한다.
    Optional<Settlement> findByIdempotencyKey(String idempotencyKey);

    // 지급 원천(출연 계약·주문 항목)에 보류·지급 유형과 관계없이 지급이 있는지 확인한다.
    boolean existsBySourceTypeAndSourceId(SettlementSourceType sourceType, Long sourceId);

    // 보류에 지급 건이 하나라도 있는지 확인한다.
    boolean existsByPointHoldId(Long pointHoldId);
}
