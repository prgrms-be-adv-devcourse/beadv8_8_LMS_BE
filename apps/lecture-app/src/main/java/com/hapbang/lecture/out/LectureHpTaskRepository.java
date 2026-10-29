package com.hapbang.lecture.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.lecture.domain.LectureHpTask;

// 관리자 목록의 선택 필터는 Specification으로 조회한다 (LectureHpTaskSupport.search)
public interface LectureHpTaskRepository extends JpaRepository<LectureHpTask, Long>,
        JpaSpecificationExecutor<LectureHpTask> {

    /** 요청 ID(지갑 멱등 키)로 작업을 찾는다. 요청 ID는 유일하다 */
    Optional<LectureHpTask> findByRequestId(String requestId);

    /**
     * 선점: 대기 중이고 다음 시도 시각이 지났거나, 실행 중이지만 선점이 만료된 작업만 {@code PROCESSING}으로 바꾼다 (D57 (2)·(3)).
     * 선점에 성공할 때만 횟수를 올린다. 자동 재시도 한도에 닿은 작업은 선점하지 않는다.
     * 버전을 함께 올려, 앞 선점의 결과 기록이 이 선점 뒤에 덮어쓰지 못하게 한다.
     *
     * @return 선점했으면 1, 아니면 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update LectureHpTask t
            set t.status = com.hapbang.lecture.domain.LectureHpTaskStatus.PROCESSING,
                t.claimToken = :token,
                t.leaseUntil = :leaseUntil,
                t.attemptCount = t.attemptCount + 1,
                t.totalAttemptCount = t.totalAttemptCount + 1,
                t.updatedAt = :now,
                t.version = t.version + 1
            where t.id = :id
              and t.attemptCount < :maxAttempts
              and ((t.status = com.hapbang.lecture.domain.LectureHpTaskStatus.PENDING and t.nextAttemptAt <= :now)
                or (t.status = com.hapbang.lecture.domain.LectureHpTaskStatus.PROCESSING and t.leaseUntil < :now))
            """)
    int claim(@Param("id") Long id, @Param("token") UUID token, @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil, @Param("maxAttempts") int maxAttempts);

    /**
     * 자동 재시도 한도에 닿은 채 선점이 만료된 작업을 {@code DEAD}로 닫는다 (D57 (3)). 새로 선점하지 않는다.
     *
     * @return 닫았으면 1, 아니면 0 (다른 실행이 먼저 닫았거나 조건이 아니다)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update LectureHpTask t
            set t.status = com.hapbang.lecture.domain.LectureHpTaskStatus.DEAD,
                t.lastErrorCode = :errorCode,
                t.lastFailedAt = :now,
                t.claimToken = null,
                t.leaseUntil = null,
                t.updatedAt = :now,
                t.version = t.version + 1
            where t.id = :id
              and t.attemptCount >= :maxAttempts
              and t.status = com.hapbang.lecture.domain.LectureHpTaskStatus.PROCESSING
              and t.leaseUntil < :now
            """)
    int markLeaseExhaustedDead(@Param("id") Long id, @Param("now") LocalDateTime now,
                               @Param("maxAttempts") int maxAttempts, @Param("errorCode") String errorCode);

    /**
     * 실행할 작업 ID: 대기 중이고 다음 시도 시각이 지났거나, 실행 중이지만 선점이 만료된 작업.
     * 한도에 닿은 채 선점이 만료된 작업도 넣는다(실행기가 {@code DEAD}로 닫는다). 다음 시도 시각 순.
     */
    @Query("""
            select t.id from LectureHpTask t
            where (t.status = com.hapbang.lecture.domain.LectureHpTaskStatus.PENDING and t.nextAttemptAt <= :now)
               or (t.status = com.hapbang.lecture.domain.LectureHpTaskStatus.PROCESSING and t.leaseUntil < :now)
            order by t.nextAttemptAt, t.id
            """)
    List<Long> findRunnableIds(@Param("now") LocalDateTime now, Pageable pageable);
}
