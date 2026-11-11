package com.hapbang.billing.out;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.PromotionFunding;

// 프로모션 지갑 충전 기록의 저장·조회를 담당하며 삭제 메서드는 제공하지 않는다.
public interface PromotionFundingRepository extends Repository<PromotionFunding, Long> {

    // 충전 기록을 저장하고 바로 DB에 반영한다(충전 작업 ID UNIQUE 충돌을 이 자리에서 알기 위해).
    PromotionFunding saveAndFlush(PromotionFunding entity);

    // 충전 작업 ID의 충전 기록을 조회한다(같은 작업의 재요청 확인).
    Optional<PromotionFunding> findByFundingWorkId(String fundingWorkId);
}
