package com.hapbang.shared.member.dto;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 다른 모듈에 넘기는 회원 정보. 복제 회원이 가지는 값과 같다.
 */
public record UserDto(
        Long id,
        String nickname,
        Role role,
        UserStatus status
) {
}
