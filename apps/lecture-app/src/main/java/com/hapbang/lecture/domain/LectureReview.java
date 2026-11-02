package com.hapbang.lecture.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 강의 리뷰·평점 (설계 4.7).
 * <ul>
 *     <li>구매 확정 후(구매 확정, 지급)의 주문 항목으로만 쓴다 (D5).</li>
 *     <li>주문 항목 1건당, 한 회원·한 강의당 살아 있는 리뷰는 하나다. 삭제는 Soft Delete이고 삭제 후 새 행으로 다시 쓴다
 *     (부분 유일 인덱스).</li>
 *     <li>항목이 반환되면 리뷰는 남기되 목록·평균 평점·리뷰 수에서 뺀다. 반환 여부는 조회할 때 항목 상태로 판정한다.</li>
 * </ul>
 */
@Entity
@Table(name = "lecture_review", schema = "lectures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LectureReview {

    private static final int MIN_RATING = 1;
    private static final int MAX_RATING = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 다른 애그리거트라 ID로만 참조한다 (FK 없음)
    @Column(nullable = false)
    private Long lectureId;

    @Column(nullable = false)
    private Long orderItemId;

    @Column(nullable = false)
    private Long reviewerUserId;

    @Column(nullable = false)
    private int rating;

    @Column(columnDefinition = "text")
    private String content;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    private LectureReview(OrderItem item, Long reviewerUserId, int rating, String content, LocalDateTime now) {
        this.lectureId = item.getLectureId();
        this.orderItemId = item.getId();
        this.reviewerUserId = reviewerUserId;
        this.rating = rating;
        this.content = content;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 본인 항목인지는 호출하는 쪽이 항목을 찾을 때 확인한다 */
    public static LectureReview write(OrderItem item, Long reviewerUserId, int rating, String content,
                                      LocalDateTime now) {
        if (!item.isReviewable()) {
            throw new LectureException(LectureErrorCode.ORDER_ITEM_NOT_REVIEWABLE,
                    item.getStatus() == OrderItemStatus.PURCHASED
                            ? "구매 확정 후 리뷰를 쓸 수 있습니다."
                            : "구매 취소·반환된 항목에는 리뷰를 쓸 수 없습니다.");
        }
        validateRating(rating);
        return new LectureReview(item, reviewerUserId, rating, normalize(content), now);
    }

    public void edit(Long userId, int rating, String content, LocalDateTime now) {
        validateWriter(userId);
        validateRating(rating);
        this.rating = rating;
        this.content = normalize(content);
        this.updatedAt = now;
    }

    public void delete(Long userId, LocalDateTime now) {
        validateWriter(userId);
        this.deletedAt = now;
        this.updatedAt = now;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    private void validateWriter(Long userId) {
        if (!reviewerUserId.equals(userId)) {
            throw new LectureException(LectureErrorCode.NOT_REVIEW_WRITER);
        }
    }

    private static void validateRating(int rating) {
        if (rating < MIN_RATING || rating > MAX_RATING) {
            throw new LectureException(LectureErrorCode.INVALID_REQUEST, "평점은 1~5 사이의 정수여야 합니다.");
        }
    }

    /** 본문은 선택이다. 비었거나 공백만 있으면 남기지 않는다 */
    private static String normalize(String content) {
        return content == null || content.isBlank() ? null : content;
    }
}
