package com.hapbang.shared.appearance.dto;

import java.util.Objects;

/**
 * 공개된 출연 리뷰 하나의 동기화용 사본 (#713). 출연 동기화 내부 API가 준다. 본문·쓴 사람은 싣지 않는다.
 * <p>
 * 리뷰는 공개 뒤 바뀌지 않아 version이 없다. 받는 쪽은 {@code reviewId}로 한 번만 넣는다.
 * 필드 이름과 값은 리뷰 공개 이벤트({@code AppearanceReviewPublishedEvent})의 같은 이름 필드와 같다.
 *
 * @param reviewId       리뷰 ID
 * @param appearanceId   출연 ID
 * @param revieweeUserId 리뷰를 받은 회원 ID
 * @param rating         별점 (1~5 정수)
 */
public record AppearanceReviewSyncDto(
        Long reviewId,
        Long appearanceId,
        Long revieweeUserId,
        int rating
) {

    // 빠진 응답은 Client가 읽을 수 없는 응답으로 거절한다
    public AppearanceReviewSyncDto {
        Objects.requireNonNull(reviewId, "reviewId는 필수입니다.");
        Objects.requireNonNull(appearanceId, "appearanceId는 필수입니다.");
        Objects.requireNonNull(revieweeUserId, "revieweeUserId는 필수입니다.");
    }
}
