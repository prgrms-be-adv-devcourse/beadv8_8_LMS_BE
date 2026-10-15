package com.hapbang.appearance.out;

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

    /** 출연 행을 배타 잠금({@code FOR UPDATE})으로 읽는다. 같은 출연의 취소 요청·응답·자동 거절을 차례로 처리한다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Appearance a where a.id = :id")
    Optional<Appearance> findByIdForUpdate(@Param("id") Long id);

    /**
     * 출연의 출처 (모집에서 성립했으면 모집글 ID가 있다). 출연 ID를 함께 담는다: 모집글 ID 하나만 고르면
     * 요청서 출처(모집글 ID null)의 결과 행이 null이 되어 "없음"으로 읽히기 때문이다.
     */
    interface SourceRef {

        Long getId();

        Long getRecruitId();
    }
}
