package com.hapbang.appearance.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.appearance.domain.Appearance;

public interface AppearanceRepository extends JpaRepository<Appearance, Long> {

    /** 출연 요청서에서 성립한 출연 */
    Optional<Appearance> findByRequestId(Long requestId);

    /** 모집 지원에서 성립한 출연들 (지원 목록 응답용) */
    List<Appearance> findByApplicationIdIn(Collection<Long> applicationIds);
}
