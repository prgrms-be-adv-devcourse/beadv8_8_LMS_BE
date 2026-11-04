package com.hapbang.shared.appearance.event;

import java.time.LocalDateTime;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.dto.AppearanceParticipantRole;

/**
 * 출연 리뷰가 공개됐다 (#513). 공개된 리뷰 하나에 한 번 발행된다. 블라인드 공개로 두 리뷰가 함께 공개되면 2개가 나간다.
 * 회원 모듈이 받은 사람의 평점(별점 합계·개수)을 계산하는 데 쓴다. 본문은 담지 않는다(필요하면 출연 조회 API로 읽는다).
 * <p>
 * Kafka {@value AppearanceEventTopic#NAME} 토픽에 key {@code appearance-review-reviewee:<받은 사람 회원 ID>}로 나간다.
 * 받은 사람 단위로 같은 partition에 모인다. 최소 한 번 전달이라 받는 쪽은 {@code reviewId}로 중복을 거른다.
 *
 * @param reviewId       리뷰 ID
 * @param appearanceId   출연 ID
 * @param reviewerUserId 리뷰를 쓴 회원 ID
 * @param revieweeUserId 리뷰를 받은 회원 ID
 * @param revieweeRole   받은 회원이 그 출연에서 맡은 역할
 * @param rating         별점 (1~5 정수)
 * @param publishedAt    공개 시각 (KST)
 */
@Externalized(AppearanceEventTopic.NAME + "::#{'appearance-review-reviewee:' + #this.revieweeUserId()}")
public record AppearanceReviewPublishedEvent(
        Long reviewId,
        Long appearanceId,
        Long reviewerUserId,
        Long revieweeUserId,
        AppearanceParticipantRole revieweeRole,
        int rating,
        LocalDateTime publishedAt
) {
}
