package com.hapbang.member.app;

import static com.hapbang.member.fixture.PostgresErrorFixture.CHECK_VIOLATION;
import static com.hapbang.member.fixture.PostgresErrorFixture.UNIQUE_VIOLATION;
import static com.hapbang.member.fixture.PostgresErrorFixture.violation;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;

/** 회원 유일 위반 판별 (#646, D48). DB 없이 PostgreSQL 오류 필드로 확인한다 */
class UserUniqueViolationTest {

    @Test
    @DisplayName("이메일 유일 위반은 DUPLICATE_EMAIL, 닉네임 유일 위반은 DUPLICATE_NICKNAME")
    void translatesUserViolations() {
        assertThat(UserUniqueViolation.translate(violation(UNIQUE_VIOLATION, UserUniqueViolation.EMAIL_UNIQUE)))
                .isInstanceOfSatisfying(MemberException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.DUPLICATE_EMAIL));
        assertThat(UserUniqueViolation.translate(violation(UNIQUE_VIOLATION, UserUniqueViolation.NICKNAME_UNIQUE)))
                .isInstanceOfSatisfying(MemberException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.DUPLICATE_NICKNAME));
    }

    @Test
    @DisplayName("다른 제약, UNIQUE가 아닌 위반, 제약 이름이 없거나 드라이버 오류가 없으면 받은 예외 그대로")
    void keepsOtherViolations() {
        DataIntegrityViolationException otherConstraint = violation(UNIQUE_VIOLATION, "pk_users");
        DataIntegrityViolationException checkViolation = violation(CHECK_VIOLATION, UserUniqueViolation.NICKNAME_UNIQUE);
        DataIntegrityViolationException noConstraint = violation(UNIQUE_VIOLATION, null);
        DataIntegrityViolationException messageOnly = new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"" + UserUniqueViolation.NICKNAME_UNIQUE + "\"");

        assertThat(UserUniqueViolation.translate(otherConstraint)).isSameAs(otherConstraint);
        assertThat(UserUniqueViolation.translate(checkViolation)).isSameAs(checkViolation);
        assertThat(UserUniqueViolation.translate(noConstraint)).isSameAs(noConstraint);
        assertThat(UserUniqueViolation.translate(messageOnly)).isSameAs(messageOnly);
    }
}
