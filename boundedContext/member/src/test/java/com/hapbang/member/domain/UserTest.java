package com.hapbang.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("역할을 바꾸면 닉네임·상태는 그대로다")
    void changeRole() {
        User user = User.join("tester1@example.com", "tester1");

        user.changeRole(Role.INFLUENCER);

        assertThat(user.getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(user.getNickname()).isEqualTo("tester1");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("닉네임을 바꾸면 앞뒤 공백을 지워 저장하고 역할·상태는 그대로다")
    void changeNickname() {
        User user = User.join("tester1@example.com", "tester1");
        user.changeRole(Role.INFLUENCER);

        user.changeNickname("  새 닉네임 ");

        assertThat(user.getNickname()).isEqualTo("새 닉네임");
        assertThat(user.getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("규칙에 맞지 않는 닉네임으로 바꾸면 예외이고 닉네임은 그대로다")
    void changeNickname_invalid() {
        User user = User.join("tester1@example.com", "tester1");

        assertThatThrownBy(() -> user.changeNickname("민"))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_NICKNAME_LENGTH);
        assertThat(user.getNickname()).isEqualTo("tester1");
    }

    @Test
    @DisplayName("규칙에 맞지 않는 닉네임으로는 가입할 수 없다")
    void join_invalidNickname() {
        assertThatThrownBy(() -> User.join("tester1@example.com", "🔥민규"))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_NICKNAME_CHARACTER);
    }

    @Test
    @DisplayName("프로필 이미지 키를 저장하고 null이면 지운다. 닉네임·역할은 그대로다")
    void changeProfileImage() {
        User user = User.join("tester1@example.com", "tester1");

        user.changeProfileImage("profiles/1/image.png");
        assertThat(user.getProfileImageKey()).isEqualTo("profiles/1/image.png");

        user.changeProfileImage(null);
        assertThat(user.getProfileImageKey()).isNull();
        assertThat(user.getNickname()).isEqualTo("tester1");
        assertThat(user.getRole()).isEqualTo(Role.USER);
    }
}
