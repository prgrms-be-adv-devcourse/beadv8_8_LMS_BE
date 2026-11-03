package com.hapbang.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

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

    @Test
    @DisplayName("탈퇴하면 상태가 WITHDRAWN이 되고 탈퇴 시각이 함께 남는다 (#593)")
    void withdraw() {
        User user = User.join("tester1@example.com", "tester1");
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 12, 0);

        user.withdraw(now);

        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(user.getDeletedAt()).isEqualTo(now);
        // 개인정보는 복구를 위해 그대로 둔다 (30일 뒤 마스킹)
        assertThat(user.getEmail()).isEqualTo("tester1@example.com");
        assertThat(user.getNickname()).isEqualTo("tester1");
    }

    @Test
    @DisplayName("정상 회원이 아니면 탈퇴할 수 없다 (#593)")
    void withdraw_notActive() {
        User user = User.join("tester1@example.com", "tester1");
        user.withdraw(LocalDateTime.of(2026, 11, 3, 12, 0));

        assertThatThrownBy(() -> user.withdraw(LocalDateTime.of(2026, 11, 4, 12, 0)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("탈퇴하고 30일이 지나기 전에만 복구할 수 있다 (#593)")
    void canRestore() {
        User user = User.join("tester1@example.com", "tester1");
        LocalDateTime withdrawnAt = LocalDateTime.of(2026, 11, 3, 12, 0);
        assertThat(user.canRestore(withdrawnAt)).isFalse();

        user.withdraw(withdrawnAt);

        assertThat(user.canRestore(withdrawnAt.plusDays(30).minusNanos(1))).isTrue();
        assertThat(user.canRestore(withdrawnAt.plusDays(30))).isFalse();
    }

    @Test
    @DisplayName("복구하면 정상 회원으로 돌아오고 탈퇴 시각이 지워진다. 기한이 지나면 복구할 수 없다 (#593)")
    void restore() {
        LocalDateTime withdrawnAt = LocalDateTime.of(2026, 11, 3, 12, 0);
        User restored = User.join("tester1@example.com", "tester1");
        restored.withdraw(withdrawnAt);
        User expired = User.join("tester2@example.com", "tester2");
        expired.withdraw(withdrawnAt);

        restored.restore(withdrawnAt.plusDays(10));

        assertThat(restored.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(restored.getDeletedAt()).isNull();
        assertThatThrownBy(() -> expired.restore(withdrawnAt.plusDays(31))).isInstanceOf(IllegalStateException.class);
    }
}
