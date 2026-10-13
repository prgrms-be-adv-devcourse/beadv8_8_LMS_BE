package com.hapbang.member.in;

import java.util.List;

import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;

/** 회원 프로필 상세 (공개 정보 + 등록된 채널). 목록용 MemberProfileResponse와 달리 채널을 넣는다 */
public record MemberProfileDetailResponse(Long id, String nickname, Role role, String profileImageUrl,
                                          List<ChannelResponse> channels) {

    public static MemberProfileDetailResponse from(User user, List<InfluencerChannel> channels) {
        MemberProfileResponse profile = MemberProfileResponse.from(user);
        return new MemberProfileDetailResponse(profile.id(), profile.nickname(), profile.role(),
                profile.profileImageUrl(), channels.stream().map(ChannelResponse::from).toList());
    }
}
