package com.hapbang.appearance.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.appearance.domain.AppearanceCancelRequest;
import com.hapbang.appearance.domain.AppearanceCancelRequestStatus;

public interface AppearanceCancelRequestRepository extends JpaRepository<AppearanceCancelRequest, Long> {

    /** 이 출연에 보낸 취소 요청 수 (상태 무관, 횟수 제한용) */
    int countByAppearanceId(Long appearanceId);

    /** 이 출연의 이 상태 취소 요청 (처리 중인 요청은 출연당 하나뿐이다) */
    Optional<AppearanceCancelRequest> findByAppearanceIdAndStatus(Long appearanceId,
                                                                  AppearanceCancelRequestStatus status);

    /** 이 출연의 취소 요청 이력, 순번 순 */
    List<AppearanceCancelRequest> findByAppearanceIdOrderBySequence(Long appearanceId);

    /** 자동 거절 배치: 이 상태 취소 요청 중 출연의 방송 예정 일시가 기준 시각 이전·같은 출연 ID, 방송 예정 일시 이른 순 */
    @Query("""
            select c.appearanceId from AppearanceCancelRequest c
            join Appearance a on a.id = c.appearanceId
            where c.status = :status and a.scheduledAt <= :now
            order by a.scheduledAt, c.appearanceId
            """)
    List<Long> findAppearanceIdsDue(@Param("status") AppearanceCancelRequestStatus status,
                                    @Param("now") LocalDateTime now, Pageable pageable);
}
