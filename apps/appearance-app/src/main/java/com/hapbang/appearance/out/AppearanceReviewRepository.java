package com.hapbang.appearance.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.appearance.domain.AppearanceReview;

public interface AppearanceReviewRepository extends JpaRepository<AppearanceReview, Long> {

    /** 이 회원이 이 출연에 리뷰를 썼는지 */
    boolean existsByAppearanceIdAndReviewerId(Long appearanceId, Long reviewerId);

    /** 이 회원이 이 출연에 쓴 리뷰 (출연당 작성자 1명에 하나) */
    Optional<AppearanceReview> findByAppearanceIdAndReviewerId(Long appearanceId, Long reviewerId);

    /** 이 출연의 리뷰 (최대 2건: 호스트가 쓴 것, 게스트가 쓴 것) */
    List<AppearanceReview> findByAppearanceId(Long appearanceId);

    /**
     * 작성 기한 만료 공개 대상 출연 ID (#515): 공개 전 리뷰가 있고 출연 확인 시각이 기준 시각 이전·같은 출연.
     * 출연 확인이 오래된 순, 같으면 출연 ID 순.
     *
     * @param cutoff 지금 − 작성 기한. 확인 시각이 이 시각 이전이면 기한이 지났다
     */
    @Query("""
            select r.appearanceId from AppearanceReview r
            join Appearance a on a.id = r.appearanceId
            where r.publishedAt is null and a.confirmedAt <= :cutoff
            group by r.appearanceId, a.confirmedAt
            order by a.confirmedAt, r.appearanceId""")
    List<Long> findAppearanceIdsWithUnpublishedConfirmedUpTo(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /**
     * 이 회원이 받은 공개 리뷰 (#514). 최신 공개순(공개 시각 desc, 같으면 ID desc)으로 고정한다.
     * 정렬이 쿼리에 있어 {@code pageable}은 정렬 없이 넘긴다 (인덱스 {@code idx_appearance_reviews_reviewee_id_published_at}).
     */
    @Query("""
            select r from AppearanceReview r
            where r.revieweeId = :revieweeId and r.publishedAt is not null
            order by r.publishedAt desc, r.id desc""")
    Page<AppearanceReview> findPublishedByReviewee(@Param("revieweeId") Long revieweeId, Pageable pageable);
}
