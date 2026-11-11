package com.hapbang.lecture.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.lecture.domain.LectureHpAttemptResult;
import com.hapbang.lecture.domain.LectureHpTaskAttempt;
import com.hapbang.lecture.domain.LectureHpTaskType;

public interface LectureHpTaskAttemptRepository extends JpaRepository<LectureHpTaskAttempt, Long> {

    Optional<LectureHpTaskAttempt> findByTaskIdAndClaimToken(Long taskId, UUID claimToken);

    /**
     * 주문의 보류가 이 지갑 실패 코드로 확정 거절됐는지 (D76, 결제 실패 사유). 결제 실패 응답을 사유별로 나눌 때 쓴다.
     * 사유는 주문에 저장하지 않고 보류 작업의 시도 기록에서 읽는다
     */
    @Query("""
            select count(a) > 0 from LectureHpTaskAttempt a, LectureHpTask t
            where a.taskId = t.id and t.orderId = :orderId
              and t.taskType = :taskType and a.result = :result and a.errorCode = :errorCode""")
    boolean existsByOrderAndResult(@Param("orderId") Long orderId, @Param("taskType") LectureHpTaskType taskType,
                                   @Param("result") LectureHpAttemptResult result,
                                   @Param("errorCode") String errorCode);

    /** 작업의 시도 이력 (시도 순) */
    List<LectureHpTaskAttempt> findByTaskIdOrderByAttemptNo(Long taskId);

    /**
     * 결과를 기록하지 못한 채 선점이 만료된 시도를 {@code LEASE_EXPIRED}로 닫는다 (D57 (3)).
     * 다시 선점하거나 한도 초과로 닫을 때, 그 선점을 얻은 트랜잭션에서만 부른다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update LectureHpTaskAttempt a
            set a.result = com.hapbang.lecture.domain.LectureHpAttemptResult.LEASE_EXPIRED,
                a.completedAt = :now
            where a.taskId = :taskId and a.result is null
            """)
    int closeOpenAttempts(@Param("taskId") Long taskId, @Param("now") LocalDateTime now);
}
