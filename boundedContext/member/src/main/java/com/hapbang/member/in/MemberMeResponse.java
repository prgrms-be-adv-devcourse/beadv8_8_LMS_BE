package com.hapbang.member.in;

import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/** 내 정보 응답 */
public record MemberMeResponse(Long id, String email, String nickname, Role role, UserStatus status) {

    public static MemberMeResponse from(User user) {
        return new MemberMeResponse(user.getId(), user.getEmail(), user.getNickname(), user.getRole(), user.getStatus());
    }
}
