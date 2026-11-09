package com.hapbang.member.in;

import java.time.LocalDateTime;

import com.hapbang.member.app.ReceivedAppearanceReview;
import com.hapbang.member.domain.User;
import com.hapbang.shared.appearance.dto.AppearanceParticipantRole;
import com.hapbang.shared.appearance.dto.AppearanceReceivedReviewDto;

/**
 * 회원이 받은 출연 리뷰 (#555)
 *
 * @param revieweeRole 받은 회원이 그 출연에서 맡은 역할 (HOST: 호스트로 받음, GUEST: 게스트로 받음)
 * @param content      본문. 없으면 null
 * @param publishedAt  공개 시각 (KST)
 */
public record AppearanceReviewResponse(Long reviewId, Long appearanceId, AppearanceParticipantRole revieweeRole,
                                       int rating, String content, LocalDateTime publishedAt,
                                       ReviewerResponse reviewer) {

    public static AppearanceReviewResponse from(ReceivedAppearanceReview received, String reviewerProfileImageUrl) {
        AppearanceReceivedReviewDto review = received.review();
        return new AppearanceReviewResponse(review.reviewId(), review.appearanceId(), review.revieweeRole(),
                review.rating(), review.content(), review.publishedAt(),
                ReviewerResponse.of(review.reviewerUserId(), received.reviewer(), reviewerProfileImageUrl));
    }

    /**
     * 리뷰를 쓴 회원. 탈퇴했거나 회원 DB에 없으면 닉네임은 "탈퇴한 회원", 사진은 null이다 (정지 회원은 그대로 보인다)
     *
     * @param profileImageUrl 받은 뒤 대개 10분 이상 쓸 수 있는 조회 주소(서버 자격 증명이 바뀌는 짧은 동안은 더 짧을 수 있다. 만료되거나 불러오지 못하면, 사진을 바꾼 직후의 옛 주소 포함, 다시 조회한다). 사진이 없으면 null
     */
    public record ReviewerResponse(Long id, String nickname, String profileImageUrl) {

        static final String WITHDRAWN_NICKNAME = "탈퇴한 회원";

        static ReviewerResponse of(Long id, User user, String profileImageUrl) {
            if (user == null || user.getStatus().isWithdrawn()) {
                return new ReviewerResponse(id, WITHDRAWN_NICKNAME, null);
            }
            return new ReviewerResponse(id, user.getNickname(), profileImageUrl);
        }
    }
}
