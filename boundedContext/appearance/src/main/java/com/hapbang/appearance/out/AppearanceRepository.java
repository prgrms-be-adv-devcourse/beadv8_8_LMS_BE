package com.hapbang.appearance.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.appearance.domain.Appearance;

public interface AppearanceRepository extends JpaRepository<Appearance, Long> {

    /** 출연 요청서에서 성립한 출연 */
    Optional<Appearance> findByRequestId(Long requestId);
}
