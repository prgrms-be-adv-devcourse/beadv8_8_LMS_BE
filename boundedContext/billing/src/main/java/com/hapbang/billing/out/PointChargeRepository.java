package com.hapbang.billing.out;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.PointCharge;

// 충전 건 정보의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다.
public interface PointChargeRepository extends Repository<PointCharge, Long> {

    // 충전 건 정보를 저장한다.
    PointCharge save(PointCharge entity);

    // 기본키 ID로 충전 건 정보를 조회한다.
    Optional<PointCharge> findById(Long id);

    // PG 주문 ID에 해당하는 충전 건을 조회한다.
    Optional<PointCharge> findByPgOrderId(String pgOrderId);
}
