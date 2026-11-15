package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LectureRecommendToolsTruncateTest {

    private static final String EMOJI = "😀";

    @Test
    @DisplayName("100자를 넘는 관심사는 코드포인트 100개로 자르고, 이모지를 반으로 자르지 않는다")
    void keepsSurrogatePair() {
        String interest = "가".repeat(99) + EMOJI + "나";

        String truncated = LectureRecommendTools.truncate(interest);

        assertThat(truncated).isEqualTo("가".repeat(99) + EMOJI);
        assertThat(truncated.codePointCount(0, truncated.length())).isEqualTo(100);
    }

    @Test
    @DisplayName("이모지 100개는 UTF-16으로 200자지만 API 검사처럼 100자로 보고 자르지 않는다")
    void countsCodePoints() {
        String interest = EMOJI.repeat(100);

        assertThat(LectureRecommendTools.truncate(interest)).isEqualTo(interest);
    }

    @Test
    @DisplayName("자른 끝의 공백은 뺀다. 100자 이하와 null은 그대로다")
    void stripsAndKeepsShort() {
        assertThat(LectureRecommendTools.truncate("가".repeat(99) + "  나")).isEqualTo("가".repeat(99));
        assertThat(LectureRecommendTools.truncate("데이터 분석")).isEqualTo("데이터 분석");
        assertThat(LectureRecommendTools.truncate(null)).isNull();
    }
}
