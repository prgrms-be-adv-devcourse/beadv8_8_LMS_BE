package com.hapbang.member.in;

import java.util.List;
import java.util.Map;

import com.hapbang.member.domain.AppearanceRatingSummary;
import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;

/**
 * 회원 공개 프로필 (다른 사람이 보는 정보라 이메일은 넣지 않는다). profileImageUrl은 1시간 조회 주소, 사진이 없으면 null.
 * appearanceRating은 받은 출연 리뷰 전체 평점이다 (#555, 역할별 평점은 프로필 상세에만).
 * contentCategories는 고른 순서의 분야(코드·이름), 없으면 빈 배열 (#711)
 */
public record MemberProfileResponse(Long id, String nickname, Role role, String profileImageUrl,
                                    RatingResponse appearanceRating, List<ContentCategoryResponse> contentCategories) {

    public static MemberProfileResponse from(User user, String profileImageUrl, AppearanceRatingSummary appearanceRating,
                                             Map<String, String> categoryNames) {
        return new MemberProfileResponse(user.getId(), user.getNickname(), user.getRole(), profileImageUrl,
                RatingResponse.from(appearanceRating.total()),
                ContentCategoryResponse.of(user.getContentCategories(), categoryNames));
    }
}
