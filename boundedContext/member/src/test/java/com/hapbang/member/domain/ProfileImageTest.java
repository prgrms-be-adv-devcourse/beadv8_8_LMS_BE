package com.hapbang.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProfileImageTest {

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png", "image/webp", "image/JPEG; charset=UTF-8"})
    @DisplayName("jpeg·png·webp는 대소문자·파라미터와 상관없이 통과한다")
    void validate_allowedType(String contentType) {
        assertThatCode(() -> ProfileImage.validate(contentType, 100)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/gif", "application/pdf", "text/plain"})
    @DisplayName("허용하지 않는 형식이면 INVALID_PROFILE_IMAGE_TYPE")
    void validate_notAllowedType(String contentType) {
        assertThatThrownBy(() -> ProfileImage.validate(contentType, 100))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_PROFILE_IMAGE_TYPE);
    }

    @Test
    @DisplayName("형식이 없으면 INVALID_PROFILE_IMAGE_TYPE")
    void validate_noType() {
        assertThatThrownBy(() -> ProfileImage.validate(null, 100))
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_PROFILE_IMAGE_TYPE);
    }

    @Test
    @DisplayName("크기는 1바이트~5MB만 통과한다")
    void validate_sizeBoundary() {
        assertThatCode(() -> ProfileImage.validate("image/png", 1)).doesNotThrowAnyException();
        assertThatCode(() -> ProfileImage.validate("image/png", ProfileImage.MAX_SIZE)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 5L * 1024 * 1024 + 1})
    @DisplayName("0바이트, Content-Length 없음(-1), 5MB 초과면 INVALID_PROFILE_IMAGE_SIZE")
    void validate_invalidSize(long contentLength) {
        assertThatThrownBy(() -> ProfileImage.validate("image/png", contentLength))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_PROFILE_IMAGE_SIZE);
    }

    @Test
    @DisplayName("S3 key는 member/profile/{회원ID}/{uuid}이고 올릴 때마다 다르다")
    void newKey() {
        String first = ProfileImage.newKey(7L);
        String second = ProfileImage.newKey(7L);

        assertThat(first).matches("member/profile/7/[0-9a-f-]{36}");
        assertThat(second).isNotEqualTo(first);
    }
}
