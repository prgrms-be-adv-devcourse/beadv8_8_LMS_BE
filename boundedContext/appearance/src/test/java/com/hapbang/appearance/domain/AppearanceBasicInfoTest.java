package com.hapbang.appearance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 출연 요청서·모집글이 함께 쓰는 기본 정보 값 객체.
 */
class AppearanceBasicInfoTest {

    private static final String TITLE = "주말 게임 합방";
    private static final String URL = "https://www.youtube.com/@host";
    private static final String CONTENT = "같이 플레이하는 콘텐츠입니다.";

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("형식이 맞으면 만든다")
    void of() {
        AppearanceBasicInfo info = AppearanceBasicInfo.of(TITLE, URL, CONTENT);

        assertThat(info.getTitle()).isEqualTo(TITLE);
        assertThat(info.getHostChannelUrl()).isEqualTo(URL);
        assertThat(info.getContent()).isEqualTo(CONTENT);
    }

    @Test
    @DisplayName("제목 공백·101자, http(s)가 아닌 URL, 설명 5,001자는 INVALID_REQUEST")
    void invalid() {
        assertInvalid(() -> AppearanceBasicInfo.of(" ", URL, CONTENT));
        assertInvalid(() -> AppearanceBasicInfo.of("가".repeat(101), URL, CONTENT));
        assertInvalid(() -> AppearanceBasicInfo.of(TITLE, "ftp://host", CONTENT));
        assertInvalid(() -> AppearanceBasicInfo.of(TITLE, URL, "가".repeat(5_001)));
    }

    @Test
    @DisplayName("보낸 값만 바꾼 새 객체를 돌려주고, 원래 객체는 바뀌지 않는다")
    void withChanges() {
        AppearanceBasicInfo info = AppearanceBasicInfo.of(TITLE, URL, CONTENT);

        AppearanceBasicInfo changed = info.withChanges("새 제목", null, null);

        assertThat(changed.getTitle()).isEqualTo("새 제목");
        assertThat(changed.getContent()).isEqualTo(CONTENT);
        assertThat(changed.getHostChannelUrl()).isEqualTo(URL);
        assertThat(info.getTitle()).isEqualTo(TITLE);
    }

    @Test
    @DisplayName("바꾸려는 값 중 하나라도 형식이 틀리면 INVALID_REQUEST")
    void withInvalidChanges() {
        AppearanceBasicInfo info = AppearanceBasicInfo.of(TITLE, URL, CONTENT);

        assertInvalid(() -> info.withChanges("새 제목", null, "not-url"));
    }
}
