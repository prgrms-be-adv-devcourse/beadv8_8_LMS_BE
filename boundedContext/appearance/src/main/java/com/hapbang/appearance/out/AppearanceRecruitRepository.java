package com.hapbang.appearance.out;

import java.time.LocalDateTime;
import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;

public interface AppearanceRecruitRepository extends JpaRepository<AppearanceRecruit, Long> {

    /** 공개 목록: 이 상태이면서 모집 마감 일시가 기준 시각 이후인 모집글 */
    Page<AppearanceRecruit> findByStatusAndRecruitDeadlineAfter(AppearanceRecruitStatus status, LocalDateTime now,
                                                                Pageable pageable);

    /** 내가 올린 모집글 목록 */
    Page<AppearanceRecruit> findByHostIdAndStatusIn(Long hostId, Collection<AppearanceRecruitStatus> statuses,
                                                    Pageable pageable);
}
