package com.hapbang.member.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

class UserTest {

    @Test
    @DisplayName("가입하면 역할은 USER, 상태는 ACTIVE이고 프로필 이미지는 없다")
    void join() {
        User user = User.join("tester1@example.com", "tester1");

        assertThat(user.getEmail()).isEqualTo("tester1@example.com");
        assertThat(user.getNickname()).isEqualTo("tester1");
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getProfileImageKey()).isNull();
        assertThat(user.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("다른 모듈에 넘길 회원 정보에 닉네임·역할·상태가 담긴다")
    void toDto() {
        User user = User.join("tester1@example.com", "tester1");

        UserDto dto = user.toDto();

        assertThat(dto.nickname()).isEqualTo("tester1");
        assertThat(dto.role()).isEqualTo(Role.USER);
        assertThat(dto.status()).isEqualTo(UserStatus.ACTIVE);
    }
}
