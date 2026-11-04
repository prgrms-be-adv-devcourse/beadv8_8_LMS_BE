package com.hapbang.appearance.out;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceRequestStatus;

public interface AppearanceRequestRepository extends JpaRepository<AppearanceRequest, Long> {

    /** 같은 호스트·게스트 사이에 진행 중인 요청서가 있는지 */
    boolean existsByHostIdAndGuestIdAndStatusIn(Long hostId, Long guestId, Collection<AppearanceRequestStatus> statuses);

    /** 보낸 요청 목록 */
    Page<AppearanceRequest> findByHostIdAndStatusIn(Long hostId, Collection<AppearanceRequestStatus> statuses,
                                                    Pageable pageable);

    /** 받은 요청 목록 */
    Page<AppearanceRequest> findByGuestIdAndStatusIn(Long guestId, Collection<AppearanceRequestStatus> statuses,
                                                     Pageable pageable);

    /** 수락 기한 만료 배치: 이 상태이면서 수락 기한이 기준 시각 이전·같은 요청서 ID, 기한 순 */
    @Query("""
            select r.id from AppearanceRequest r
            where r.status = :status and r.acceptDeadline <= :now
            order by r.acceptDeadline, r.id
            """)
    List<Long> findIdsByStatusAndAcceptDeadlineBefore(@Param("status") AppearanceRequestStatus status,
                                                      @Param("now") LocalDateTime now, Pageable pageable);

    /** 보류 재확인 배치: 이 상태이면서 기준 시각 이전·같은 때 만든 요청서 ID, 오래된 순 */
    @Query("""
            select r.id from AppearanceRequest r
            where r.status = :status and r.createdAt <= :cutoff
            order by r.createdAt, r.id
            """)
    List<Long> findIdsByStatusAndCreatedBefore(@Param("status") AppearanceRequestStatus status,
                                               @Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /** 서비스 간 HP 대사 대상 (#581): 이 시각 이후 바뀐 요청서 ID */
    @Query("select r.id from AppearanceRequest r where r.updatedAt >= :since")
    List<Long> findIdsUpdatedSince(@Param("since") LocalDateTime since);

    /** 호스트가 보낸 요청서 중 이 상태인 수 (탈퇴 막힌 이유, #624) */
    long countByHostIdAndStatus(Long hostId, AppearanceRequestStatus status);

    /** 이 회원이 호스트인 요청서 중 {@code statuses} 상태인 수 (탈퇴 회원 파기 때 진행 중인 글 확인, #675) */
    long countByHostIdAndStatusIn(Long hostId, Collection<AppearanceRequestStatus> statuses);

    /**
     * 탈퇴 회원 파기(#675): 이 회원이 호스트인 요청서의 호스트 채널 URL을 {@code masked}로 덮는다.
     * {@code excluded} 상태의 요청서와 이미 덮은 요청서는 건드리지 않는다(같은 이벤트를 다시 받아도 결과가 같다).
     * 버전·수정 시각은 바꾸지 않는다(업무 상태 변경이 아니다).
     *
     * @return 덮은 요청서 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AppearanceRequest r set r.basicInfo.hostChannelUrl = :masked
            where r.hostId = :hostId and r.status not in :excluded and r.basicInfo.hostChannelUrl <> :masked""")
    int maskHostChannelUrl(@Param("hostId") Long hostId, @Param("masked") String masked,
                           @Param("excluded") Collection<AppearanceRequestStatus> excluded);

    /** 게스트가 받은 요청서 중 이 상태인 수 (탈퇴 막힌 이유, #624) */
    long countByGuestIdAndStatus(Long guestId, AppearanceRequestStatus status);
}
