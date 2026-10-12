package com.hapbang.billing.out;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.PointChargeUsage;

// 충전 원금 배분 정보의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다.
public interface PointChargeUsageRepository extends Repository<PointChargeUsage, Long> {

    // 충전 원금 배분 정보를 저장한다.
    PointChargeUsage save(PointChargeUsage entity);

    // 기본키 ID로 충전 원금 배분 정보를 조회한다.
    Optional<PointChargeUsage> findById(Long id);

    // 충전 건과 보류의 ID 조합에 해당하는 원금 배분 내역을 조회한다.
    Optional<PointChargeUsage> findByChargeIdAndPointHoldId(Long chargeId, Long pointHoldId);
}
