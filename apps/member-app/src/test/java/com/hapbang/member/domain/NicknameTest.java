package com.hapbang.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class NicknameTest {

    @Test
    @DisplayName("규칙에 맞으면 앞뒤 공백만 지우고 돌려준다")
    void validate() {
        assertThat(Nickname.validate("  민규_123 ")).isEqualTo("민규_123");
        assertThat(Nickname.validate("Minkyu Choi")).isEqualTo("Minkyu Choi");
    }

    @ParameterizedTest
    @ValueSource(strings = {"민규", "가나다라마바사아자차카타"})
    @DisplayName("2자와 12자는 허용한다")
    void validate_lengthBoundary(String input) {
        assertThat(Nickname.validate(input)).isEqualTo(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"민", "가나다라마바사아자차카타파", "", "   "})
    @DisplayName("2자 미만이거나 12자를 넘으면 예외")
    void validate_invalidLength(String input) {
        assertThatThrownBy(() -> Nickname.validate(input))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_NICKNAME_LENGTH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"민규!", "ㅋㅋ", "🔥민규", "민규-1"})
    @DisplayName("허용하지 않는 문자가 있으면 예외 (특수문자·자음만·이모지)")
    void validate_invalidCharacter(String input) {
        assertThatThrownBy(() -> Nickname.validate(input))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_NICKNAME_CHARACTER);
    }

    @Test
    @DisplayName("공백을 연속으로 쓰면 예외")
    void validate_consecutiveSpaces() {
        assertThatThrownBy(() -> Nickname.validate("민  규"))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_NICKNAME_SPACE);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Minkyu Choi (최민규)|Minkyu Choi",
            "🔥게임하는 민규🔥 [LIVE]|게임하는 민규 LIVE",
            "민규|민규"
    })
    @DisplayName("구글 이름을 닉네임 규칙에 맞게 정리한다 (회원 모듈 설계 1-3의 예시)")
    void fromGoogleName(String googleName, String expected) {
        assertThat(Nickname.fromGoogleName(googleName)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"🎮🎮", "!", ""})
    @DisplayName("정리 후 2자 미만이면 기본 닉네임(회원 + 숫자 4자리)")
    void fromGoogleName_default(String googleName) {
        assertThat(Nickname.fromGoogleName(googleName)).matches("회원\\d{4}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Minkyu Choi (최민규)", "🔥게임하는 민규🔥 [LIVE]", "🎮🎮", "  a  b  c  "})
    @DisplayName("정리한 닉네임은 항상 규칙을 통과한다")
    void fromGoogleName_alwaysValid(String googleName) {
        String nickname = Nickname.fromGoogleName(googleName);

        assertThat(Nickname.validate(nickname)).isEqualTo(nickname);
    }
}
