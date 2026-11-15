package com.hapbang.auxiliary.chatbot.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 인플루언서 프로필 임베딩 글 (#719) */
class InfluencerProfileTextTest {

    @Test
    @DisplayName("분야 이름 + 자기소개. 자기소개의 이메일·전화번호는 지운다")
    void textScrubbed() {
        InfluencerProfileText text = InfluencerProfileText.of(List.of("게임", "음악"),
                " 롤 방송합니다. 문의 hello@example.com, 010-1234-5678 ");

        assertThat(text.text()).isEqualTo("분야: 게임, 음악\n소개: 롤 방송합니다. 문의 [이메일], [전화번호]");
        assertThat(text.text()).doesNotContain("hello@example.com", "010-1234-5678");
        assertThat(text.hash()).hasSize(64);
    }

    @Test
    @DisplayName("분야와 자기소개가 모두 없으면 빈 글. 한쪽만 있으면 그것만")
    void emptyAndPartial() {
        assertThat(InfluencerProfileText.of(List.of(), null).isEmpty()).isTrue();
        assertThat(InfluencerProfileText.of(List.of(), "   ").isEmpty()).isTrue();
        assertThat(InfluencerProfileText.of(List.of("게임"), null).text()).isEqualTo("분야: 게임");
        assertThat(InfluencerProfileText.of(List.of(), "요리합니다").text()).isEqualTo("소개: 요리합니다");
    }

    @Test
    @DisplayName("글이 같으면 해시가 같고, 분야 순서나 소개가 바뀌면 해시가 다르다")
    void hash() {
        String hash = InfluencerProfileText.of(List.of("게임", "음악"), "안녕").hash();

        assertThat(InfluencerProfileText.of(List.of("게임", "음악"), "안녕").hash()).isEqualTo(hash);
        assertThat(InfluencerProfileText.of(List.of("음악", "게임"), "안녕").hash()).isNotEqualTo(hash);
        assertThat(InfluencerProfileText.of(List.of("게임", "음악"), "안녕하세요").hash()).isNotEqualTo(hash);
    }
}
