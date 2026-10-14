package com.hapbang.member.in;

import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/** 내 정보 응답 */
public record MemberMeResponse(Long id, String email, String nickname, Role role, UserStatus status,
                               String profileImageUrl) {

    public static MemberMeResponse from(User user) {
        // TODO(S3): profileImageKey를 URL로 바꾼다. S3 공통 설정 전이라 지금은 항상 null
        return new MemberMeResponse(user.getId(), user.getEmail(), user.getNickname(), user.getRole(),
                user.getStatus(), null);
    }
}
