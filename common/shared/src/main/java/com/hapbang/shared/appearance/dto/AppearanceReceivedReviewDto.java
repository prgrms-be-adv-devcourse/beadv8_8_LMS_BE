package com.hapbang.shared.appearance.dto;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 회원이 받은 공개 출연 리뷰 한 건 (#514). 회원 모듈이 프로필의 리뷰 목록을 만들 때 출연 내부 API로 읽는다.
 * 시각은 KST {@code LocalDateTime}이다 (offset 없음, 출연 내부 API 공통).
 *
 * @param reviewId       리뷰 ID. 공개 이벤트(#513)의 {@code reviewId}와 같다
 * @param appearanceId   출연 ID
 * @param reviewerUserId 리뷰를 쓴 회원 ID
 * @param revieweeRole   받은 회원(조회한 회원)이 그 출연에서 맡은 역할
 * @param rating         별점 (1~5 정수)
 * @param content        본문. 쓰지 않았으면 {@code null}
 * @param publishedAt    공개 시각
 */
public record AppearanceReceivedReviewDto(
        Long reviewId,
        Long appearanceId,
        Long reviewerUserId,
        AppearanceParticipantRole revieweeRole,
        int rating,
        String content,
        LocalDateTime publishedAt
) {

    // 본문만 비어 있을 수 있다. 빠진 응답은 Client가 읽을 수 없는 응답으로 거절한다
    public AppearanceReceivedReviewDto {
        Objects.requireNonNull(reviewId, "reviewId는 필수입니다.");
        Objects.requireNonNull(appearanceId, "appearanceId는 필수입니다.");
        Objects.requireNonNull(reviewerUserId, "reviewerUserId는 필수입니다.");
        Objects.requireNonNull(revieweeRole, "revieweeRole은 필수입니다.");
        Objects.requireNonNull(publishedAt, "publishedAt은 필수입니다.");
    }
}
