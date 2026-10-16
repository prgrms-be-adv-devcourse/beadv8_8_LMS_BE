package com.hapbang.appearance.out;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceStatus;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.domain.HpTaskType;

import jakarta.persistence.LockModeType;

public interface AppearanceRepository extends JpaRepository<Appearance, Long> {

    /** 출연 요청서에서 성립한 출연 */
    Optional<Appearance> findByRequestId(Long requestId);

    /** 모집 지원에서 성립한 출연들 (지원 목록 응답용) */
    List<Appearance> findByApplicationIdIn(Collection<Long> applicationIds);

    /** 내가 호스트인 출연 목록 */
    Page<Appearance> findByHostIdAndStatusIn(Long hostId, Collection<AppearanceStatus> statuses, Pageable pageable);

    /** 내가 게스트인 출연 목록 */
    Page<Appearance> findByGuestIdAndStatusIn(Long guestId, Collection<AppearanceStatus> statuses, Pageable pageable);

    /**
     * 출연의 출처 모집글 ID만 읽는다 (엔티티를 영속성 컨텍스트에 올리지 않는다).
     * 취소 요청 수락 때 모집글을 먼저 잠그기 위해 쓴다 (잠그는 순서 모집글 → 출연).
     */
    Optional<SourceRef> findSourceRefById(Long id);

    /**
     * 모집글에서 성립해 아직 진행 중인 출연(이 상태들)을 배타 잠금으로 읽는다. 모집 전체 취소용 (#239).
     * 출연 ID 순으로 잠가 여러 출연을 잠글 때도 순서가 같다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Appearance a where a.recruitId = :recruitId and a.status in :statuses order by a.id")
    List<Appearance> findByRecruitForUpdate(@Param("recruitId") Long recruitId,
                                            @Param("statuses") Collection<AppearanceStatus> statuses);

    /**
     * 자동 확인 대상 출연 ID: 이 상태이고 방송 예정 일시가 기준 시각 이전 (= 방송 예정 일시 + 확인 기간 ≤ 지금).
     * 방송 예정 일시가 오래된 순 (#282)
     */
    @Query("""
            select a.id from Appearance a
            where a.status = :status and a.scheduledAt <= :cutoff
            order by a.scheduledAt, a.id
            """)
    List<Long> findIdsByStatusAndScheduledAtUpTo(@Param("status") AppearanceStatus status,
                                                 @Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /** 출연 행을 배타 잠금({@code FOR UPDATE})으로 읽는다. 같은 출연의 취소 요청·응답·자동 거절을 차례로 처리한다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Appearance a where a.id = :id")
    Optional<Appearance> findByIdForUpdate(@Param("id") Long id);

    /**
     * 게스트의 출연 수익 요약 (#301): (출연 상태, 지급·위약금 작업 상태)별 건수·출연료 합계·위약금 합계.
     * 작업은 출연마다 많아야 하나라(요청 ID가 출연별로 유일하고, 확인과 일방 취소는 함께 생기지 않음) 출연이 두 번 세지지 않는다.
     */
    @Query("""
            select new com.hapbang.appearance.out.AppearanceEarningGroup(
                a.status, t.status, count(a), sum(a.fee), sum(coalesce(a.penaltyAmount, 0L)))
            from Appearance a
            left join HpTask t on t.appearanceId = a.id and t.taskType in :taskTypes
            where a.guestId = :guestId
            group by a.status, t.status
            """)
    List<AppearanceEarningGroup> summarizeEarnings(@Param("guestId") Long guestId,
                                                   @Param("taskTypes") Collection<HpTaskType> taskTypes);

    /**
     * 게스트의 출연 수익 목록 (#301). 조건은 {@code AppearanceEarningRule.Condition} 모양 그대로다.
     * 빈 목록 대신 자리표시 값을 넘기고 {@code has…} 플래그로 끈다 ({@code in ()}을 피함).
     * 정렬: 상태 기준 시각 내림차순, 같으면 출연 ID 내림차순 (출연 1건 = 1행이라 순서가 정해진다).
     */
    @Query(value = """
            select new com.hapbang.appearance.out.AppearanceEarningRow(
                a, t.status, coalesce(t.completedAt, a.confirmedAt, a.canceledAt, a.absentAt, a.updatedAt))
            from Appearance a
            left join HpTask t on t.appearanceId = a.id and t.taskType in :taskTypes
            where a.guestId = :guestId
              and ((:hasPlain = true and a.status in :plain)
                or (:hasWithTask = true and a.status in :withTask and t.status in :taskStatuses)
                or (:includeMissingTask = true and a.status in :withTask and t.id is null))
            order by coalesce(t.completedAt, a.confirmedAt, a.canceledAt, a.absentAt, a.updatedAt) desc, a.id desc
            """,
            countQuery = """
                    select count(a)
                    from Appearance a
                    left join HpTask t on t.appearanceId = a.id and t.taskType in :taskTypes
                    where a.guestId = :guestId
                      and ((:hasPlain = true and a.status in :plain)
                        or (:hasWithTask = true and a.status in :withTask and t.status in :taskStatuses)
                        or (:includeMissingTask = true and a.status in :withTask and t.id is null))
                    """)
    Page<AppearanceEarningRow> findEarnings(@Param("guestId") Long guestId,
                                            @Param("taskTypes") Collection<HpTaskType> taskTypes,
                                            @Param("hasPlain") boolean hasPlain,
                                            @Param("plain") Collection<AppearanceStatus> plain,
                                            @Param("hasWithTask") boolean hasWithTask,
                                            @Param("withTask") Collection<AppearanceStatus> withTask,
                                            @Param("taskStatuses") Collection<HpTaskStatus> taskStatuses,
                                            @Param("includeMissingTask") boolean includeMissingTask,
                                            Pageable pageable);

    /**
     * 출연의 출처 (모집에서 성립했으면 모집글 ID가 있다). 출연 ID를 함께 담는다: 모집글 ID 하나만 고르면
     * 요청서 출처(모집글 ID null)의 결과 행이 null이 되어 "없음"으로 읽히기 때문이다.
     */
    interface SourceRef {

        Long getId();

        Long getRecruitId();
    }
}
