package com.hapbang.billing.out;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.PromotionWithdrawal;

// 프로모션 지갑 회수 기록의 저장·조회를 담당하며 삭제 메서드는 제공하지 않는다.
public interface PromotionWithdrawalRepository extends Repository<PromotionWithdrawal, Long> {

    // 회수 기록을 저장하고 바로 DB에 반영한다(회수 작업 ID UNIQUE 충돌을 이 자리에서 알기 위해).
    PromotionWithdrawal saveAndFlush(PromotionWithdrawal entity);

    // 회수 작업 ID의 회수 기록을 조회한다(같은 작업의 재요청 확인).
    Optional<PromotionWithdrawal> findByWithdrawalWorkId(String withdrawalWorkId);
}
