package com.hapbang.member.in;

import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/** 내 정보 응답. profileImageUrl은 1시간 동안 쓸 수 있는 조회 주소, 사진이 없으면 null */
public record MemberMeResponse(Long id, String email, String nickname, Role role, UserStatus status,
                               String profileImageUrl) {

    public static MemberMeResponse from(User user, String profileImageUrl) {
        return new MemberMeResponse(user.getId(), user.getEmail(), user.getNickname(), user.getRole(),
                user.getStatus(), profileImageUrl);
    }
}
