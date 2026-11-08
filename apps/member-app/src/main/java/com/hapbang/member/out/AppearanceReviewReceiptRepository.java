package com.hapbang.member.out;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.member.domain.AppearanceReviewReceipt;

/** 출연 평점에 반영한 리뷰 저장소 (#555) */
public interface AppearanceReviewReceiptRepository extends JpaRepository<AppearanceReviewReceipt, Long> {

    /**
     * 처음 받은 리뷰면 기록하고 1, 이미 반영한 리뷰면 아무것도 하지 않고 0을 돌려준다.
     * 같은 리뷰가 동시에 와도 PK가 하나만 들어가게 한다(중복을 예외 없이 거른다)
     */
    @Modifying
    @Query(value = """
            insert into members.appearance_review_receipt
                (review_id, appearance_id, reviewer_id, reviewee_id, reviewee_role, rating, published_at, created_at)
            values (:reviewId, :appearanceId, :reviewerId, :revieweeId, :revieweeRole, :rating, :publishedAt, :now)
            on conflict (review_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("reviewId") long reviewId,
                       @Param("appearanceId") long appearanceId,
                       @Param("reviewerId") long reviewerId,
                       @Param("revieweeId") long revieweeId,
                       @Param("revieweeRole") String revieweeRole,
                       @Param("rating") int rating,
                       @Param("publishedAt") LocalDateTime publishedAt,
                       @Param("now") LocalDateTime now);
}
