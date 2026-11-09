package com.hapbang.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class BioTest {

    @Test
    @DisplayName("앞뒤 공백을 지우고 돌려준다")
    void validate() {
        assertThat(Bio.validate("  메이플 위주로 방송해요 ")).isEqualTo("메이플 위주로 방송해요");
    }

    @Test
    @DisplayName("100자는 허용하고, 앞뒤 공백은 길이에 세지 않는다")
    void validate_maxLength() {
        String bio = "가".repeat(100);

        assertThat(Bio.validate(bio)).isEqualTo(bio);
        assertThat(Bio.validate("  " + bio + "  ")).isEqualTo(bio);
    }

    @Test
    @DisplayName("101자면 INVALID_BIO_LENGTH")
    void validate_tooLong() {
        assertThatThrownBy(() -> Bio.validate("가".repeat(101)))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_BIO_LENGTH);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("null이거나 공백뿐이면 없음(null)")
    void validate_empty(String input) {
        assertThat(Bio.validate(input)).isNull();
    }
}
