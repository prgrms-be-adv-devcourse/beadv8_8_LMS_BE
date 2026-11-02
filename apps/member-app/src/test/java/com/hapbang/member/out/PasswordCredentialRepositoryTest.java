package com.hapbang.member.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.hapbang.member.domain.PasswordCredential;
import com.hapbang.member.domain.User;
import com.hapbang.testsupport.RepositoryTest;

@RepositoryTest
class PasswordCredentialRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 10, 0);

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordCredentialRepository passwordCredentialRepository;

    @Test
    @DisplayName("이메일로 비밀번호 정보와 회원을 함께 찾는다")
    void findByUserEmail() {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));
        passwordCredentialRepository.save(PasswordCredential.register(user, "{bcrypt}hash-1", NOW));

        assertThat(passwordCredentialRepository.findByUserEmail("tester1@example.com"))
                .hasValueSatisfying(found -> {
                    assertThat(found.getUser().getId()).isEqualTo(user.getId());
                    assertThat(found.getPasswordHash()).isEqualTo("{bcrypt}hash-1");
                });
        assertThat(passwordCredentialRepository.findByUserEmail("none@example.com")).isEmpty();
    }

    @Test
    @DisplayName("회원 ID로 비밀번호 정보를 찾고, 비밀번호로 가입했는지 확인한다 (구글 회원은 없다)")
    void findByUserId() {
        User passwordMember = userRepository.save(User.join("tester1@example.com", "tester1"));
        User googleMember = userRepository.save(User.join("tester2@example.com", "tester2"));
        passwordCredentialRepository.save(PasswordCredential.register(passwordMember, "{bcrypt}hash-1", NOW));

        assertThat(passwordCredentialRepository.findByUserId(passwordMember.getId())).isPresent();
        assertThat(passwordCredentialRepository.existsByUserId(passwordMember.getId())).isTrue();
        assertThat(passwordCredentialRepository.findByUserId(googleMember.getId())).isEmpty();
        assertThat(passwordCredentialRepository.existsByUserId(googleMember.getId())).isFalse();
    }

    @Test
    @DisplayName("한 회원에 비밀번호 정보를 두 번 만들면 유일 제약 위반이다")
    void save_duplicate() {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));
        passwordCredentialRepository.saveAndFlush(PasswordCredential.register(user, "{bcrypt}hash-1", NOW));

        assertThatThrownBy(() -> passwordCredentialRepository.saveAndFlush(
                PasswordCredential.register(user, "{bcrypt}hash-2", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
