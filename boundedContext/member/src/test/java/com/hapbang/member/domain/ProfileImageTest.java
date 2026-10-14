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
    @DisplayName("파일 앞부분(매직 넘버)이 헤더 형식과 맞으면 통과한다")
    void validateSignature_matches() {
        assertThatCode(() -> ProfileImage.validateSignature("image/jpeg", bytes(0xFF, 0xD8, 0xFF, 0xE0)))
                .doesNotThrowAnyException();
        assertThatCode(() -> ProfileImage.validateSignature("image/png",
                bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A))).doesNotThrowAnyException();
        assertThatCode(() -> ProfileImage.validateSignature("image/WEBP; q=1",
                bytes('R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P'))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("헤더와 내용 형식이 다르거나 너무 짧으면 PROFILE_IMAGE_CONTENT_MISMATCH")
    void validateSignature_mismatch() {
        byte[] jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0);
        byte[] riffNotWebp = bytes('R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E');

        for (Runnable call : new Runnable[] {
                () -> ProfileImage.validateSignature("image/png", jpeg),
                () -> ProfileImage.validateSignature("image/webp", riffNotWebp),
                () -> ProfileImage.validateSignature("image/jpeg", bytes(0xFF, 0xD8)),
                () -> ProfileImage.validateSignature("image/png", new byte[0])}) {
            assertThatThrownBy(call::run)
                    .isInstanceOf(MemberException.class)
                    .extracting("errorCode").isEqualTo(MemberErrorCode.PROFILE_IMAGE_CONTENT_MISMATCH);
        }
    }

    @Test
    @DisplayName("S3 key는 member/profile/{회원ID}/{uuid}이고 올릴 때마다 다르다")
    void newKey() {
        String first = ProfileImage.newKey(7L);
        String second = ProfileImage.newKey(7L);

        assertThat(first).matches("member/profile/7/[0-9a-f-]{36}");
        assertThat(second).isNotEqualTo(first);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }
}
