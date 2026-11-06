package com.hapbang.lecture.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.lecture.domain.LectureReview;

import jakarta.persistence.LockModeType;

/**
 * 반환 처리 중·반환된 항목의 리뷰는 남기되 목록·평균 평점·리뷰 수에서 뺀다 (D5, D59 (3)). 반환 여부는 컬럼으로 복사하지 않고 조회할 때
 * 주문 항목과 JOIN해 판정한다. 같은 강의 스키마 안이라 모듈 경계 위반이 아니다.
 */
public interface LectureReviewRepository extends JpaRepository<LectureReview, Long> {

    /**
     * 목록·평균 평점·리뷰 수에 들어가는 리뷰(별칭 {@code r}). 삭제된 리뷰와 반환 처리 중·반환된 항목의 리뷰는 빠진다.
     * 강의 평점순 정렬({@link LectureRepository#findOnSaleOrderByRating})도 이 조건을 쓴다. 뒤에 {@code and …}로 조건을 붙인다.
     * 주문 항목 상태 조건은 {@code OrderItemStatus.countsReview()}와 같다(테스트가 모든 상태에서 대조한다).
     */
    String VISIBLE_REVIEWS = "from LectureReview r join OrderItem i on i.id = r.orderItemId "
            + "where r.deletedAt is null "
            + "and i.status not in (com.hapbang.lecture.domain.OrderItemStatus.RETURN_PENDING, "
            + "com.hapbang.lecture.domain.OrderItemStatus.RETURNED)";

    Optional<LectureReview> findByIdAndDeletedAtIsNull(Long id);

    /**
     * 살아 있는 리뷰를 행 잠금으로 읽는다. 리뷰를 바꾸는 요청(고치기·지우기·반환된 항목 리뷰 대체)과 이의 제기 인정의 버전 올리기를 한 줄로 세워
     * 리뷰 버전(#712) 충돌을 막는다 (D82).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LectureReview r where r.id = :id and r.deletedAt is null")
    Optional<LectureReview> findAliveForUpdate(@Param("id") Long id);

    /** 주문 항목의 살아 있는 리뷰 ID. 부분 유일 인덱스로 많아야 하나다 */
    @Query("select r.id from LectureReview r where r.orderItemId = :orderItemId and r.deletedAt is null")
    Optional<Long> findAliveIdByOrderItemId(@Param("orderItemId") Long orderItemId);

    /**
     * 리뷰 동기화 내부 API(#712): {@code afterId}보다 큰 리뷰를 ID 오름차순으로 {@code limit}개와 그 주문 항목의 상태. 지운 리뷰도 싣는다.
     * 평점 반영 여부를 정하려고 주문 항목 상태를 함께 읽는다
     */
    @Query("select new com.hapbang.lecture.out.ReviewWithItemStatus(r, i.status) from LectureReview r "
            + "join OrderItem i on i.id = r.orderItemId where r.id > :afterId order by r.id")
    List<ReviewWithItemStatus> findSyncReviews(@Param("afterId") long afterId, Limit limit);

    /**
     * 회원이 이 강의에 쓴 살아 있는 리뷰를 행 잠금으로 읽는다. 부분 유일 인덱스로 많아야 하나다.
     * 새 리뷰가 반환된 항목의 리뷰를 대체할 때 그 리뷰를 바꾸므로 잠근다 ({@link #findAliveForUpdate(Long)}와 같은 이유)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LectureReview r "
            + "where r.lectureId = :lectureId and r.reviewerUserId = :reviewerUserId and r.deletedAt is null")
    Optional<LectureReview> findAliveForUpdate(@Param("lectureId") Long lectureId,
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
