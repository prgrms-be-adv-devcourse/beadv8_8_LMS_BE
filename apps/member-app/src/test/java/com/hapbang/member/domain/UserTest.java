package com.hapbang.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Test
    @DisplayName("복구 기한은 탈퇴 시각 + 30일이고, 탈퇴하지 않았으면 없다 (#593)")
    void restorableUntil() {
        User user = User.join("tester1@example.com", "tester1");
        assertThat(user.restorableUntil()).isNull();
        LocalDateTime withdrawnAt = LocalDateTime.of(2026, 11, 3, 12, 0);

        user.withdraw(withdrawnAt);

        assertThat(user.restorableUntil()).isEqualTo(LocalDateTime.of(2026, 12, 3, 12, 0));
    }

    @Test
    @DisplayName("기한 안에 지갑 장애로 복구가 실패했으면 복구 기한이 7일 늘어난다 (#594)")
    void restoreGrace() {
        LocalDateTime withdrawnAt = LocalDateTime.of(2026, 11, 3, 12, 0);
        User user = User.join("tester1@example.com", "tester1");
        user.withdraw(withdrawnAt);

        user.recordRestoreFailure(withdrawnAt.plusDays(29));

        assertThat(user.getRestoreFailedAt()).isEqualTo(withdrawnAt.plusDays(29));
        assertThat(user.restorableUntil()).isEqualTo(withdrawnAt.plusDays(37));
        assertThat(user.canRestore(withdrawnAt.plusDays(36))).isTrue();
        assertThat(user.canRestore(withdrawnAt.plusDays(37))).isFalse();
    }

    @Test
    @DisplayName("원래 기한이 지난 뒤의 실패나 두 번째 실패는 남기지 않아 유예가 더 늘지 않는다 (#594)")
    void restoreGraceOnlyOnce() {
        LocalDateTime withdrawnAt = LocalDateTime.of(2026, 11, 3, 12, 0);
        User late = User.join("tester1@example.com", "tester1");
        late.withdraw(withdrawnAt);
        User twice = User.join("tester2@example.com", "tester2");
        twice.withdraw(withdrawnAt);

        late.recordRestoreFailure(withdrawnAt.plusDays(30));
        twice.recordRestoreFailure(withdrawnAt.plusDays(10));
        twice.recordRestoreFailure(withdrawnAt.plusDays(33));

        assertThat(late.getRestoreFailedAt()).isNull();
        assertThat(late.restorableUntil()).isEqualTo(withdrawnAt.plusDays(30));
        assertThat(twice.getRestoreFailedAt()).isEqualTo(withdrawnAt.plusDays(10));
        assertThat(twice.restorableUntil()).isEqualTo(withdrawnAt.plusDays(37));
    }

    @Test
    @DisplayName("복구하면 복구 실패 기록이 지워져, 다시 탈퇴했을 때 예전 실패로 유예를 받지 않는다 (#594)")
    void restoreFailureCleared() {
        LocalDateTime withdrawnAt = LocalDateTime.of(2026, 11, 3, 12, 0);
        User user = User.join("tester1@example.com", "tester1");
        user.withdraw(withdrawnAt);
        user.recordRestoreFailure(withdrawnAt.plusDays(1));

        user.restore(withdrawnAt.plusDays(2));
        user.withdraw(withdrawnAt.plusDays(3));

        assertThat(user.getRestoreFailedAt()).isNull();
        assertThat(user.restorableUntil()).isEqualTo(withdrawnAt.plusDays(33));
    }

    @Test
    @DisplayName("복구 기한이 지난 탈퇴 회원을 마스킹하면 이메일은 지워지고 닉네임은 회원 ID를 넣은 값이 되며 사진 연결이 끊긴다 (#594)")
    void mask() {
        LocalDateTime withdrawnAt = LocalDateTime.of(2026, 11, 3, 12, 0);
        User user = User.join("tester1@example.com", "tester1");
        ReflectionTestUtils.setField(user, "id", 5L);
        user.changeProfileImage("member/profile/5");
        user.withdraw(withdrawnAt);

        user.mask(withdrawnAt.plusDays(30));

        assertThat(user.getEmail()).isNull();
        assertThat(user.getNickname()).isEqualTo("탈퇴회원#5");
        assertThat(user.getProfileImageKey()).isNull();
        assertThat(user.getMaskedAt()).isEqualTo(withdrawnAt.plusDays(30));
        assertThat(user.isMasked()).isTrue();
        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(user.canRestore(withdrawnAt.plusDays(30))).isFalse();
    }

    @Test
    @DisplayName("마스킹한 닉네임은 닉네임 규칙 밖이라 사용자가 같은 값으로 바꿀 수 없다 (#594)")
    void maskedNicknameNotAllowedForUsers() {
        assertThatThrownBy(() -> Nickname.validate(User.MASKED_NICKNAME_PREFIX + 5))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_NICKNAME_CHARACTER);
    }

    @Test
    @DisplayName("정상 회원, 복구 기한(유예 포함) 안의 회원, 이미 마스킹한 회원은 마스킹할 수 없다 (#594)")
    void maskNotAllowed() {
        LocalDateTime withdrawnAt = LocalDateTime.of(2026, 11, 3, 12, 0);
        User active = User.join("tester1@example.com", "tester1");
        User restorable = User.join("tester2@example.com", "tester2");
        restorable.withdraw(withdrawnAt);
        User inGrace = User.join("tester3@example.com", "tester3");
        inGrace.withdraw(withdrawnAt);
        inGrace.recordRestoreFailure(withdrawnAt.plusDays(29));
        User masked = User.join("tester4@example.com", "tester4");
        masked.withdraw(withdrawnAt);
        masked.mask(withdrawnAt.plusDays(30));

        assertThatThrownBy(() -> active.mask(withdrawnAt.plusDays(40))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> restorable.mask(withdrawnAt.plusDays(29))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> inGrace.mask(withdrawnAt.plusDays(36))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> masked.mask(withdrawnAt.plusDays(31))).isInstanceOf(IllegalStateException.class);
        inGrace.mask(withdrawnAt.plusDays(37));
        assertThat(inGrace.isMasked()).isTrue();
    }
}
