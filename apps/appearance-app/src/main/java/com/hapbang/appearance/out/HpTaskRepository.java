package com.hapbang.appearance.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskStatus;

public interface HpTaskRepository extends JpaRepository<HpTask, Long> {

    /** 멱등 키(= Billing 요청 ID)로 작업 하나를 찾는다. 키는 유니크다 */
    Optional<HpTask> findByIdempotencyKey(String idempotencyKey);

    /**
     * 재시도 배치가 실행할 작업 ID. 대기 중이고 다음 시도 시각이 지났으며, 이미 한 번 이상 시도했거나 만든 지 대기 시간이 지난 작업.
     * 방금 기록돼 요청 안에서 바로 실행 중인 작업과 겹치지 않도록, 처음 시도 전 작업은 대기 시간을 둔다. 다음 시도 시각 순.
     */
    @Query("""
            select t.id from HpTask t
            where t.status = com.hapbang.appearance.domain.HpTaskStatus.PENDING
              and t.nextAttemptAt <= :now
              and (t.attemptCount > 0 or t.createdAt <= :graceCutoff)
            order by t.nextAttemptAt, t.id
            """)
    List<Long> findRunnableIds(@Param("now") LocalDateTime now, @Param("graceCutoff") LocalDateTime graceCutoff,
                               Pageable pageable);

    /** 상태별 작업 수 (지표용) */
    long countByStatus(HpTaskStatus status);
}
