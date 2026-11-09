package com.hapbang.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContentCategoriesTest {

    @Test
    @DisplayName("0개와 5개는 허용하고, 고른 순서를 지킨 복사본을 돌려준다")
    void validate() {
        List<String> five = new ArrayList<>(List.of("MAPLESTORY", "GAME", "MUKBANG", "MUSIC", "TRAVEL"));

        List<String> validated = ContentCategories.validate(five);
        five.clear();

        assertThat(ContentCategories.validate(List.of())).isEmpty();
        assertThat(validated).containsExactly("MAPLESTORY", "GAME", "MUKBANG", "MUSIC", "TRAVEL");
    }

    @Test
    @DisplayName("null이면 빈 목록")
    void validate_null() {
        assertThat(ContentCategories.validate(null)).isEmpty();
    }

    @Test
    @DisplayName("6개면 INVALID_CONTENT_CATEGORY_COUNT")
    void validate_tooMany() {
        assertThatThrownBy(() -> ContentCategories.validate(
                List.of("GAME", "MUKBANG", "BEAUTY", "MUSIC", "SPORTS", "TRAVEL")))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_CONTENT_CATEGORY_COUNT);
    }

    @Test
    @DisplayName("같은 코드가 두 번 있으면 DUPLICATE_CONTENT_CATEGORY")
    void validate_duplicate() {
        assertThatThrownBy(() -> ContentCategories.validate(List.of("GAME", "MUKBANG", "GAME")))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.DUPLICATE_CONTENT_CATEGORY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    @DisplayName("빈 코드면 INVALID_CONTENT_CATEGORY")
    void validate_blank(String code) {
        assertThatThrownBy(() -> ContentCategories.validate(List.of("GAME", code)))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_CONTENT_CATEGORY);
    }

    @Test
    @DisplayName("null 코드면 INVALID_CONTENT_CATEGORY")
    void validate_nullCode() {
        assertThatThrownBy(() -> ContentCategories.validate(Arrays.asList("GAME", null)))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_CONTENT_CATEGORY);
    }
}
