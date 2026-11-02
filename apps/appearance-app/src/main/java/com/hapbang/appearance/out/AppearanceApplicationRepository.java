package com.hapbang.appearance.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.appearance.domain.AppearanceApplication;
import com.hapbang.appearance.domain.AppearanceApplicationStatus;

public interface AppearanceApplicationRepository extends JpaRepository<AppearanceApplication, Long> {

    /** 이 게스트가 이 모집글에 지원한 적이 있는지 (상태 무관, 재지원 불가) */
    boolean existsByRecruitIdAndGuestId(Long recruitId, Long guestId);

    /** 이 게스트의 이 모집글 지원 (재지원 불가라 최대 한 건) */
    Optional<AppearanceApplication> findByRecruitIdAndGuestId(Long recruitId, Long guestId);

    /** 모집글의 이 상태 지원 전부 (모집 마감 때 대기 중 지원 정리용) */
    List<AppearanceApplication> findByRecruitIdAndStatus(Long recruitId, AppearanceApplicationStatus status);

    /** 모집글의 지원자 목록 (호스트) */
    Page<AppearanceApplication> findByRecruitIdAndStatusIn(Long recruitId,
                                                           Collection<AppearanceApplicationStatus> statuses,
                                                           Pageable pageable);

    /** 내 지원 목록 (게스트) */
    Page<AppearanceApplication> findByGuestIdAndStatusIn(Long guestId, Collection<AppearanceApplicationStatus> statuses,
                                                         Pageable pageable);

    /** 모집글 하나의 지원자 수 (이 상태들만 센다) */
    long countByRecruitIdAndStatusIn(Long recruitId, Collection<AppearanceApplicationStatus> statuses);

    /** 모집글 여러 개의 지원자 수를 한 번에 센다 (목록 응답용). 지원이 없는 모집글은 결과에 없다 */
    @Query("""
            select a.recruitId as recruitId, count(a) as applicantCount from AppearanceApplication a
            where a.recruitId in :recruitIds and a.status in :statuses
            group by a.recruitId
            """)
    List<RecruitApplicantCount> countByRecruitIds(@Param("recruitIds") Collection<Long> recruitIds,
                                                  @Param("statuses") Collection<AppearanceApplicationStatus> statuses);

    /** 모집글별 지원자 수 (조회 결과 모양) */
    interface RecruitApplicantCount {

        Long getRecruitId();

        long getApplicantCount();
    }
}
