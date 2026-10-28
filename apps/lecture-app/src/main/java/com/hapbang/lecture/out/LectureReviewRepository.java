package com.hapbang.lecture.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.lecture.domain.LectureReview;

/**
 * 반환된 항목의 리뷰는 남기되 목록·평균 평점·리뷰 수에서 뺀다 (D5). 반환 여부는 컬럼으로 복사하지 않고 조회할 때
 * 주문 항목과 JOIN해 판정한다. 같은 강의 스키마 안이라 모듈 경계 위반이 아니다.
 */
public interface LectureReviewRepository extends JpaRepository<LectureReview, Long> {

    /**
     * 목록·평균 평점·리뷰 수에 들어가는 리뷰(별칭 {@code r}). 삭제된 리뷰와 반환된 항목의 리뷰는 빠진다.
     * 강의 평점순 정렬({@link LectureRepository#findOnSaleOrderByRating})도 이 조건을 쓴다. 뒤에 {@code and …}로 조건을 붙인다.
     */
    String VISIBLE_REVIEWS = "from LectureReview r join OrderItem i on i.id = r.orderItemId "
            + "where r.deletedAt is null and i.status <> com.hapbang.lecture.domain.OrderItemStatus.RETURNED";

    Optional<LectureReview> findByIdAndDeletedAtIsNull(Long id);

    /** 회원이 이 강의에 쓴 살아 있는 리뷰. 부분 유일 인덱스로 많아야 하나다 */
    @Query("select r from LectureReview r "
            + "where r.lectureId = :lectureId and r.reviewerUserId = :reviewerUserId and r.deletedAt is null")
    Optional<LectureReview> findAlive(@Param("lectureId") Long lectureId,
                                      @Param("reviewerUserId") Long reviewerUserId);

    /** 목록에 보이는 리뷰. 최신순으로 고정한다 */
    @Query(value = "select r " + VISIBLE_REVIEWS + " and r.lectureId = :lectureId "
            + "order by r.createdAt desc, r.id desc",
            countQuery = "select count(r) " + VISIBLE_REVIEWS + " and r.lectureId = :lectureId")
    Page<LectureReview> findVisible(@Param("lectureId") Long lectureId, Pageable pageable);

    /** 강의별 평균 평점·리뷰 수. 리뷰가 없는 강의는 빠진다 */
    @Query("select new com.hapbang.lecture.out.LectureReviewStats(r.lectureId, avg(r.rating), count(r)) "
            + VISIBLE_REVIEWS + " and r.lectureId in :lectureIds group by r.lectureId")
    List<LectureReviewStats> summarize(@Param("lectureIds") Collection<Long> lectureIds);
}
