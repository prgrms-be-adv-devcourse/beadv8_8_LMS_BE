package com.hapbang.member.in;

import com.hapbang.member.domain.AppearanceRatingSummary;
import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;

/**
 * 회원 공개 프로필 (다른 사람이 보는 정보라 이메일은 넣지 않는다). profileImageUrl은 1시간 조회 주소, 사진이 없으면 null.
 * appearanceRating은 받은 출연 리뷰 전체 평점이다 (#555, 역할별 평점은 프로필 상세에만)
 */
public record MemberProfileResponse(Long id, String nickname, Role role, String profileImageUrl,
                                    RatingResponse appearanceRating) {

    public static MemberProfileResponse from(User user, String profileImageUrl, AppearanceRatingSummary appearanceRating) {
        return new MemberProfileResponse(user.getId(), user.getNickname(), user.getRole(), profileImageUrl,
                RatingResponse.from(appearanceRating.total()));
    }
}
