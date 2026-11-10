package com.hapbang.billing.out;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.BudgetRelease;

// 쿠폰 예산 해제 기록의 저장·조회를 담당하며 삭제 메서드는 제공하지 않는다.
public interface BudgetReleaseRepository extends Repository<BudgetRelease, Long> {

    // 해제 기록을 저장하고 바로 DB에 반영한다(해제 작업 ID UNIQUE 충돌을 이 자리에서 알기 위해).
    BudgetRelease saveAndFlush(BudgetRelease entity);

    // 해제 작업 ID의 해제 기록을 조회한다(같은 작업의 재요청 확인).
    Optional<BudgetRelease> findByWorkId(String workId);
}
