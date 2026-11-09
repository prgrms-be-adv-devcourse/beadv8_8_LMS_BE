package com.hapbang.member.in;

import java.util.List;
import java.util.Map;

import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 내 정보 응답. profileImageUrl은 1시간 동안 쓸 수 있는 조회 주소, 사진이 없으면 null.
 * contentCategories는 고른 순서의 분야(코드·이름), 없으면 빈 배열. bio는 자기소개, 없으면 null (#711)
 */
public record MemberMeResponse(Long id, String email, String nickname, Role role, UserStatus status,
                               String profileImageUrl, List<ContentCategoryResponse> contentCategories, String bio) {

    public static MemberMeResponse from(User user, String profileImageUrl, Map<String, String> categoryNames) {
        return new MemberMeResponse(user.getId(), user.getEmail(), user.getNickname(), user.getRole(),
                user.getStatus(), profileImageUrl,
                ContentCategoryResponse.of(user.getContentCategories(), categoryNames), user.getBio());
    }
}
