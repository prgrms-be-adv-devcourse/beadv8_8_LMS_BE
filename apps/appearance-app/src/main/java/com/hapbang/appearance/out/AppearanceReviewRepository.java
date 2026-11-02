package com.hapbang.appearance.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.appearance.domain.AppearanceReview;

public interface AppearanceReviewRepository extends JpaRepository<AppearanceReview, Long> {

    /** 이 회원이 이 출연에 리뷰를 썼는지 */
    boolean existsByAppearanceIdAndReviewerId(Long appearanceId, Long reviewerId);

    /** 이 회원이 이 출연에 쓴 리뷰 (출연당 작성자 1명에 하나) */
    Optional<AppearanceReview> findByAppearanceIdAndReviewerId(Long appearanceId, Long reviewerId);
}
