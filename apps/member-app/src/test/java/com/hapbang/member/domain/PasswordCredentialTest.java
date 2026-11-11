package com.hapbang.member.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PasswordCredentialTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 10, 0);

    @Test
    @DisplayName("가입하면 회원·해시·바꾼 시각이 저장된다")
    void register() {
        User user = User.join("tester1@example.com", "tester1");

        PasswordCredential credential = PasswordCredential.register(user, "{bcrypt}hash-1", NOW);

        assertThat(credential.getUser()).isSameAs(user);
        assertThat(credential.getPasswordHash()).isEqualTo("{bcrypt}hash-1");
        assertThat(credential.getPasswordChangedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("비밀번호를 바꾸면 해시와 바꾼 시각이 바뀐다")
    void changePassword() {
        PasswordCredential credential =
                PasswordCredential.register(User.join("tester1@example.com", "tester1"), "{bcrypt}hash-1", NOW);

        credential.changePassword("{bcrypt}hash-2", NOW.plusDays(1));

        assertThat(credential.getPasswordHash()).isEqualTo("{bcrypt}hash-2");
        assertThat(credential.getPasswordChangedAt()).isEqualTo(NOW.plusDays(1));
    }

    @Test
    @DisplayName("문자열로 바꿔도 해시는 드러나지 않는다")
    void toString_hidesHash() {
        PasswordCredential credential =
                PasswordCredential.register(User.join("tester1@example.com", "tester1"), "{bcrypt}hash-1", NOW);

        assertThat(credential.toString()).doesNotContain("hash-1");
    }

    @Test
    @DisplayName("이메일은 앞뒤 공백을 지우고 소문자로 맞춘다")
    void memberEmail_normalize() {
        assertThat(MemberEmail.normalize("  Tester1@Example.COM ")).isEqualTo("tester1@example.com");
        assertThat(MemberEmail.normalize(null)).isEmpty();
    }

    @Test
    @DisplayName("마스킹하면 행은 남고 해시가 로그인에 쓸 수 없는 값으로 바뀐다 (#594)")
    void wipe() {
        PasswordCredential credential =
                PasswordCredential.register(User.join("tester1@example.com", "tester1"), "{bcrypt}hash-1", NOW);

        credential.wipe();

        assertThat(credential.getPasswordHash()).isEqualTo(PasswordCredential.WIPED_HASH).doesNotStartWith("{");
    }
}
