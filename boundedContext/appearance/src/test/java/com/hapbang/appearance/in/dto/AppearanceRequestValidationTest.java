package com.hapbang.appearance.in.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * 출연 요청서 요청 값의 형식 검증 (Bean Validation). 최저 출연료·기한 순서는 도메인 테스트에서 확인한다.
 */
class AppearanceRequestValidationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 12, 0);

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static CreateAppearanceRequestReqDto create(String title, String url, Integer minutes) {
        return new CreateAppearanceRequestReqDto(2L, title, url, NOW.plusDays(7), minutes, "콘텐츠 설명", 30_000L,
                NOW.plusDays(2));
    }

    private static Set<String> invalidFields(Object request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    @DisplayName("보내기: 올바른 값이면 통과한다")
    void createValid() {
        assertThat(invalidFields(create("제목", "https://www.youtube.com/@host", 60))).isEmpty();
    }

    @Test
    @DisplayName("보내기: 빈 제목, http(s)가 아닌 URL, 0분, 1441분은 실패한다")
    void createInvalid() {
        assertThat(invalidFields(create(" ", "https://a.com", 60))).containsExactly("title");
        assertThat(invalidFields(create("제목", "www.youtube.com/@host", 60))).containsExactly("hostChannelUrl");
        assertThat(invalidFields(create("제목", "https://a.com", 0))).containsExactly("expectedMinutes");
        assertThat(invalidFields(create("제목", "https://a.com", 1441))).containsExactly("expectedMinutes");
    }

    @Test
    @DisplayName("보내기: 필수 값이 없으면 항목마다 실패한다")
    void createMissing() {
        CreateAppearanceRequestReqDto empty = new CreateAppearanceRequestReqDto(null, null, null, null, null, null,
                null, null);

        assertThat(invalidFields(empty)).containsExactlyInAnyOrder("guestId", "title", "hostChannelUrl",
                "scheduledAt", "expectedMinutes", "content", "fee", "acceptDeadline");
    }

    @Test
    @DisplayName("보내기: 제목 101자는 실패한다")
    void createTitleTooLong() {
        assertThat(invalidFields(create("가".repeat(101), "https://a.com", 60))).containsExactly("title");
    }

    @Test
    @DisplayName("수정: 하나만 보내도 통과하고, 아무것도 보내지 않으면 실패한다")
    void updatePresence() {
        assertThat(invalidFields(new UpdateAppearanceRequestReqDto("새 제목", null, null))).isEmpty();
        assertThat(invalidFields(new UpdateAppearanceRequestReqDto(null, null, null)))
                .containsExactly("anyFieldPresent");
    }

    @Test
    @DisplayName("수정: 보낸 값이 공백뿐이거나 URL 형식이 아니면 실패한다")
    void updateInvalid() {
        assertThat(invalidFields(new UpdateAppearanceRequestReqDto("  ", null, null))).containsExactly("title");
        assertThat(invalidFields(new UpdateAppearanceRequestReqDto(null, "\n", null))).containsExactly("content");
        assertThat(invalidFields(new UpdateAppearanceRequestReqDto(null, null, "ftp://a.com")))
                .containsExactly("hostChannelUrl");
    }
}
