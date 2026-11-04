package com.hapbang.appearance.out;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;

import jakarta.persistence.LockModeType;

public interface AppearanceRecruitRepository extends JpaRepository<AppearanceRecruit, Long> {

    /**
     * 지원용: 모집글 행을 공유 잠금({@code FOR SHARE})으로 읽는다. 지원끼리는 서로 막지 않고,
     * 마감({@link #findByIdForUpdate})과는 한쪽이 끝날 때까지 기다린다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select r from AppearanceRecruit r where r.id = :id")
    Optional<AppearanceRecruit> findByIdForShare(@Param("id") Long id);

    /** 마감·수락용: 모집글 행을 배타 잠금({@code FOR UPDATE})으로 읽는다. 진행 중인 지원·수락이 끝난 뒤의 최신 값을 읽는다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AppearanceRecruit r where r.id = :id")
    Optional<AppearanceRecruit> findByIdForUpdate(@Param("id") Long id);

    /** 공개 목록: 이 상태이면서 모집 마감 일시가 기준 시각 이후인 모집글 */
    Page<AppearanceRecruit> findByStatusAndRecruitDeadlineAfter(AppearanceRecruitStatus status, LocalDateTime now,
                                                                Pageable pageable);

    /** 내가 올린 모집글 목록 */
    Page<AppearanceRecruit> findByHostIdAndStatusIn(Long hostId, Collection<AppearanceRecruitStatus> statuses,
                                                    Pageable pageable);

    /** 보류 재확인 배치: 이 상태이면서 기준 시각 이전·같은 때 만든 모집글 ID, 오래된 순 */
    @Query("""
            select r.id from AppearanceRecruit r
            where r.status = :status and r.createdAt <= :cutoff
            order by r.createdAt, r.id
            """)
    List<Long> findIdsByStatusAndCreatedBefore(@Param("status") AppearanceRecruitStatus status,
                                               @Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /** 기한 마감 배치: 이 상태이면서 모집 마감 일시가 기준 시각 이전·같은 모집글 ID, 마감 일시가 이른 순 */
    @Query("""
            select r.id from AppearanceRecruit r
            where r.status = :status and r.recruitDeadline <= :now
            order by r.recruitDeadline, r.id
            """)
    List<Long> findIdsByStatusAndDeadlinePassed(@Param("status") AppearanceRecruitStatus status,
                                                @Param("now") LocalDateTime now, Pageable pageable);

    /** 서비스 간 HP 대사 대상 (#581): 이 시각 이후 바뀐 모집글 ID */
    @Query("select r.id from AppearanceRecruit r where r.updatedAt >= :since")
    List<Long> findIdsUpdatedSince(@Param("since") LocalDateTime since);

    /** 호스트가 올린 모집글 중 이 상태인 수 (탈퇴 막힌 이유, #624) */
    long countByHostIdAndStatus(Long hostId, AppearanceRecruitStatus status);

    long countByHostIdAndStatusIn(Long hostId, Collection<AppearanceRecruitStatus> statuses);

    /**
     * 탈퇴 회원 파기(#675): 이 회원이 호스트인 모집글의 호스트 채널 URL을 {@code masked}로 덮는다.
     * {@code excluded} 상태의 모집글과 이미 덮은 모집글은 건드리지 않는다(같은 이벤트를 다시 받아도 결과가 같다).
     * 버전·수정 시각은 바꾸지 않는다(업무 상태 변경이 아니다).
     *
     * @return 덮은 모집글 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AppearanceRecruit r set r.basicInfo.hostChannelUrl = :masked
            where r.hostId = :hostId and r.status not in :excluded and r.basicInfo.hostChannelUrl <> :masked""")
    int maskHostChannelUrl(@Param("hostId") Long hostId, @Param("masked") String masked,
                           @Param("excluded") Collection<AppearanceRecruitStatus> excluded);
}
