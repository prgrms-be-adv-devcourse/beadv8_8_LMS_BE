package com.hapbang.appearance.app;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.AppearanceReview;
import com.hapbang.appearance.out.AppearanceReviewRepository;

import lombok.RequiredArgsConstructor;

/**
 * 출연 리뷰 저장소 접근 (#512). 트랜잭션은 호출한 쪽이 연다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceReviewSupport {

    private final AppearanceReviewRepository appearanceReviewRepository;

    /** 이 회원이 이 출연에 리뷰를 썼는지 */
    public boolean existsByAppearanceAndReviewer(Long appearanceId, Long reviewerId) {
        return appearanceReviewRepository.existsByAppearanceIdAndReviewerId(appearanceId, reviewerId);
    }

    /** 이 회원이 이 출연에 쓴 리뷰. 아직 안 썼으면 빈 값 */
    public Optional<AppearanceReview> findByAppearanceAndReviewer(Long appearanceId, Long reviewerId) {
        return appearanceReviewRepository.findByAppearanceIdAndReviewerId(appearanceId, reviewerId);
    }

    /**
     * 저장하고 바로 DB에 반영한다. 같은 회원이 같은 출연에 이미 썼으면 유니크 제약에서 예외가 난다.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException DB 제약 위반
     */
    public AppearanceReview saveNow(AppearanceReview review) {
        return appearanceReviewRepository.saveAndFlush(review);
    }

    /** 공개 시각 변경을 바로 DB에 반영한다 */
    public void flush() {
        appearanceReviewRepository.flush();
    }
}
