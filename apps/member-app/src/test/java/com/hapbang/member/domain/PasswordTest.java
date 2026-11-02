package com.hapbang.member.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordTest {

    @ParameterizedTest
    @ValueSource(strings = {"abcd1234", "Pass!@#$1", "a1~`!@#$%^&*()-_=+[]{};:'\",.<>/?|\\"})
    @DisplayName("8자 이상, 영문·숫자 포함, 영문·숫자·키보드 특수문자만이면 통과한다")
    void validate(String input) {
        assertThatCode(() -> Password.validate(input)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("8자와 64자는 허용한다")
    void validate_lengthBoundary() {
        assertThatCode(() -> Password.validate("abcdef12")).doesNotThrowAnyException();
        assertThatCode(() -> Password.validate("a1" + "x".repeat(62))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc1234"})
    @DisplayName("8자 미만이면 예외")
    void validate_tooShort(String input) {
        assertError(input, MemberErrorCode.INVALID_PASSWORD_LENGTH);
    }

    @Test
    @DisplayName("64자를 넘으면 예외")
    void validate_tooLong() {
        assertError("a1" + "x".repeat(63), MemberErrorCode.INVALID_PASSWORD_LENGTH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcd 1234", " abcd1234", "abcd1234 ", "비밀번호abcd1234", "abcd1234\t", "abcd1234🔥"})
    @DisplayName("공백·한글·탭·이모지 등 허용하지 않는 문자가 있으면 예외")
    void validate_invalidCharacter(String input) {
        assertError(input, MemberErrorCode.INVALID_PASSWORD_CHARACTER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcdefgh", "12345678", "!@#$%^&*", "abcd!@#$"})
    @DisplayName("영문과 숫자가 하나씩 없으면 예외")
    void validate_letterAndDigitRequired(String input) {
        assertError(input, MemberErrorCode.PASSWORD_LETTER_AND_DIGIT_REQUIRED);
    }

    private static void assertError(String input, MemberErrorCode errorCode) {
        assertThatThrownBy(() -> Password.validate(input))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }
}
