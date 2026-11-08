package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;

/**
 * 실제 PostgreSQL 오류로 회원 유일 위반이 409로 바뀌는지 (#646, D48). 중복 확인을 건너뛰고 바로 저장해
 * "확인과 저장 사이에 다른 요청이 먼저 저장한" 경우를 만든다
 */
@MemberIntegrationTest
class UserUniqueViolationIntegrationTest {

    @Autowired
    MemberSupport memberSupport;

    @Autowired
    UserRepository userRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("다른 회원 닉네임(대소문자만 다름)으로 바꿔 반영하면 DUPLICATE_NICKNAME")
    void nicknameIndex() {
        userRepository.save(User.join("tester1@example.com", "tester1"));
        User other = userRepository.save(User.join("tester2@example.com", "tester2"));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            memberSupport.findUser(other.getId()).changeNickname("TESTER1");
            memberSupport.flush();
        }))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.DUPLICATE_NICKNAME);
    }

    @Test
    @DisplayName("같은 이메일 회원을 바로 저장하면 DUPLICATE_EMAIL")
    void emailConstraint() {
        userRepository.save(User.join("tester1@example.com", "tester1"));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            try {
                userRepository.saveAndFlush(User.join("tester1@example.com", "tester2"));
            } catch (DataIntegrityViolationException e) {
                throw UserUniqueViolation.translate(e);
            }
        }))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.DUPLICATE_EMAIL);
    }
}
