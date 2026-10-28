package com.hapbang.appearance.out;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
