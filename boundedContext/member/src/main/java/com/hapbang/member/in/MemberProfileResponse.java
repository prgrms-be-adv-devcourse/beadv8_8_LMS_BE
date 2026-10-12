package com.hapbang.member.in;

import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;

/** 회원 공개 프로필 (다른 사람이 보는 정보라 이메일은 넣지 않는다) */
public record MemberProfileResponse(Long id, String nickname, Role role, String profileImageUrl) {

    public static MemberProfileResponse from(User user) {
        // TODO(S3): profileImageKey를 URL로 바꾼다. S3 공통 설정 전이라 지금은 항상 null
        return new MemberProfileResponse(user.getId(), user.getNickname(), user.getRole(), null);
    }
}
