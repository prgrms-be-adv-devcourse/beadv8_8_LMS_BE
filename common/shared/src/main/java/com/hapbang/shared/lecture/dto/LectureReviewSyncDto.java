package com.hapbang.shared.lecture.dto;

import java.util.Objects;

/**
 * AI 추천용 강의 리뷰 별점 하나 (#712, D82). {@code LectureReviewChangedEvent}와 리뷰 동기화 내부 API가 같은 값을 싣는다.
 * 리뷰 본문은 싣지 않는다. 지우고 다시 쓰면 새 리뷰(새 ID)다.
 *
 * @param id             리뷰 ID
 * @param lectureId      강의 ID
 * @param reviewerUserId 작성자 회원 ID
 * @param rating         별점 (1~5)
 * @param counted        강의 평균 별점·리뷰 목록에 반영되는 리뷰인지. 판단은 강의 모듈이 한다
 *                       (지금은 지운 리뷰와 반환 처리 중·반환된 주문 항목의 리뷰가 빠진다)
 * @param deleted        지웠는지(Soft Delete)
 * @param version        리뷰 원본의 버전. {@code counted}만 바뀌어도 오른다
 */
public record LectureReviewSyncDto(
        Long id,
        Long lectureId,
        Long reviewerUserId,
        int rating,
        boolean counted,
        boolean deleted,
        long version
) {

    public LectureReviewSyncDto {
        Objects.requireNonNull(id, "id는 필수입니다.");
        Objects.requireNonNull(lectureId, "lectureId는 필수입니다.");
        Objects.requireNonNull(reviewerUserId, "reviewerUserId는 필수입니다.");
    }
}
