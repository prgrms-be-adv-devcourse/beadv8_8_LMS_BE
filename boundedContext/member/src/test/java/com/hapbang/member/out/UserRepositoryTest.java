package com.hapbang.member.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.hapbang.member.domain.User;
import com.hapbang.testsupport.RepositoryTest;

@RepositoryTest
class UserRepositoryTest {

    @Autowired
    UserRepository userRepository;

    @Test
    @DisplayName("회원을 저장하면 ID와 생성일·수정일이 채워진다")
    void save() {
        User saved = userRepository.saveAndFlush(User.join("tester1@example.com", "tester1"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 이메일로 저장하면 유니크 제약 위반이다")
    void save_duplicateEmail() {
        userRepository.saveAndFlush(User.join("tester1@example.com", "tester1"));

        assertThatThrownBy(() -> userRepository.saveAndFlush(User.join("tester1@example.com", "tester2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
