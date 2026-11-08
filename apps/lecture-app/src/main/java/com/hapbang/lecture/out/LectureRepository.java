package com.hapbang.lecture.out;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureStatus;

public interface LectureRepository extends JpaRepository<Lecture, Long> {

    /** 강의({@code l})의 평균 평점·리뷰 수 집계 대상. 리뷰 목록과 같은 조건이다 (D5) */
    String VISIBLE_REVIEWS_OF_LECTURE = LectureReviewRepository.VISIBLE_REVIEWS + " and r.lectureId = l.id";

    /** 판매 중인 강의 중 제목에 검색어가 들어간 강의. 검색어의 {@code %}·{@code _}는 글자 그대로 찾는다 */
    String ON_SALE_WITH_KEYWORD = "from Lecture l where l.status = com.hapbang.lecture.domain.LectureStatus.ON_SALE "
            + "and l.deletedAt is null "
            + "and lower(l.title) like lower(concat('%', :#{escape(#keyword)}, '%')) escape :#{escapeCharacter()}";

    Page<Lecture> findByStatusAndDeletedAtIsNull(LectureStatus status, Pageable pageable);

    Page<Lecture> findByStatusAndDeletedAtIsNullAndTitleContainingIgnoreCase(LectureStatus status, String keyword,
                                                                            Pageable pageable);

    Page<Lecture> findBySellerUserId(Long sellerUserId, Pageable pageable);

    /**
     * 강의 동기화 내부 API(#712): {@code afterId}보다 큰 강의를 ID 오름차순으로 {@code limit}개. 모든 상태(검수 대기·반려·삭제 포함)다.
     * 콘텐츠는 {@code Lecture.contents}의 일괄 읽기로 한 번에 읽는다
     */
    List<Lecture> findByIdGreaterThanOrderByIdAsc(Long afterId, Limit limit);

    /**
     * 판매자 탈퇴를 막는 강의 수 (#625, D75). 삭제하지 않은 판매 중·검수 대기 강의다.
     * 검수 대기는 탈퇴 뒤 관리자가 승인하면 판매되고, 반려는 판매자가 재검수를 신청해야만 판매되어 빠진다
     */
    @Query("select count(l) from Lecture l where l.sellerUserId = :sellerUserId and l.deletedAt is null "
            + "and l.status in (com.hapbang.lecture.domain.LectureStatus.ON_SALE, "
            + "com.hapbang.lecture.domain.LectureStatus.PENDING_INSPECTION)")
    long countWithdrawalBlockingLectures(@Param("sellerUserId") Long sellerUserId);

    /**
     * 판매 중인 강의를 평균 평점 → 리뷰 수 → 최신순으로 읽는다. 리뷰가 없는 강의는 맨 뒤다.
     * 검색어가 빈 문자열이면 모든 판매 중 강의다. 정렬은 쿼리에 고정되어 있어 {@code pageable}의 정렬은 쓰지 않는다.
     */
    @Query(value = "select l " + ON_SALE_WITH_KEYWORD
            + " order by (select avg(r.rating) " + VISIBLE_REVIEWS_OF_LECTURE + ") desc nulls last, "
            + "(select count(r) " + VISIBLE_REVIEWS_OF_LECTURE + ") desc, l.createdAt desc, l.id desc",
            countQuery = "select count(l) " + ON_SALE_WITH_KEYWORD)
    Page<Lecture> findOnSaleOrderByRating(@Param("keyword") String keyword, Pageable pageable);
}
