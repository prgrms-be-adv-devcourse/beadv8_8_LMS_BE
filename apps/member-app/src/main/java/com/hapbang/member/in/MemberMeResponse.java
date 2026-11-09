package com.hapbang.member.in;

import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/** 내 정보 응답. profileImageUrl은 받은 뒤 최소 10분 쓸 수 있는 조회 주소(만료되거나 불러오지 못하면, 사진을 바꾼 직후의 옛 주소 포함, 다시 조회한다), 사진이 없으면 null */
public record MemberMeResponse(Long id, String email, String nickname, Role role, UserStatus status,
                               String profileImageUrl) {

    public static MemberMeResponse from(User user, String profileImageUrl) {
        return new MemberMeResponse(user.getId(), user.getEmail(), user.getNickname(), user.getRole(),
                user.getStatus(), profileImageUrl);
    }
}
