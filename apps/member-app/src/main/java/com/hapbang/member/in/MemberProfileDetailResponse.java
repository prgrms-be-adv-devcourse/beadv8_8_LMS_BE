package com.hapbang.member.in;

import java.util.List;

import com.hapbang.member.domain.AppearanceRatingSummary;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;

/**
 * 회원 프로필 상세 (공개 정보 + 등록된 채널 + 출연 평점). 목록용 MemberProfileResponse와 달리 채널을 넣고,
 * 출연 평점은 전체와 받은 역할별로 나눠 준다 (#555)
 */
public record MemberProfileDetailResponse(Long id, String nickname, Role role, String profileImageUrl,
                                          List<ChannelResponse> channels,
                                          AppearanceRatingResponse appearanceRating) {

    public static MemberProfileDetailResponse from(User user, String profileImageUrl, List<InfluencerChannel> channels,
                                                   AppearanceRatingSummary appearanceRating) {
        return new MemberProfileDetailResponse(user.getId(), user.getNickname(), user.getRole(), profileImageUrl,
                channels.stream().map(ChannelResponse::from).toList(), AppearanceRatingResponse.from(appearanceRating));
    }
}
