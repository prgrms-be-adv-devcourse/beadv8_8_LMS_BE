package com.hapbang.auxiliary.search.common.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 결과 0건 검색어 모음(#771 Q9, Q18): 서로 다른 회원 2명 이상, 연락처 모양·긴 말 제외 */
class ZeroResultKeywordsTest {

    private final ZeroResultKeywords keywords = new ZeroResultKeywords();

    @Test
    @DisplayName("서로 다른 회원 2명 이상이 찾은 말만 남긴다. 공백·대소문자를 맞춰 세고, 많이 나온 순으로 돌려준 뒤 비운다")
    void sharedKeywordsOnly() {
        keywords.record("롤토체스", 1L);
        keywords.record("  롤토체스 ", 2L);
        keywords.record("TFT", 1L);
        keywords.record("tft", 2L);
        keywords.record("tft", 3L);

        assertThat(keywords.flush()).containsExactly(Map.entry("tft", 3L), Map.entry("롤토체스", 2L));
        assertThat(keywords.flush()).isEmpty();
    }

    @Test
    @DisplayName("한 사람이 여러 번 찾은 말은 남기지 않는다(이름을 잘못 입력해 다시 찾는 경우)")
    void singleSearcherHidden() {
        keywords.record("홍길동", 1L);
        keywords.record("홍길동", 1L);
        keywords.record("홍길동", 1L);

        assertThat(keywords.flush()).isEmpty();
    }

    @Test
    @DisplayName("이메일·URL·전화번호 모양과 30자를 넘는 말은 여러 명이 찾아도 세지 않는다")
    void excludesContactLike() {
        for (long user = 1; user <= 3; user++) {
            keywords.record("someone@example.com", user);
            keywords.record("https://chzzk.naver.com/abc", user);
            keywords.record("www.youtube.com", user);
            keywords.record("010-1234-5678", user);
            keywords.record("가".repeat(31), user);
            keywords.record("가".repeat(30), user);
        }

        assertThat(keywords.flush()).containsOnlyKeys("가".repeat(30));
    }

    @Test
    @DisplayName("한 주기에 서로 다른 검색어는 상한까지만 세고, 로그에는 상위만 남긴다")
    void limits() {
        for (int i = 0; i < ZeroResultKeywords.MAX_KEYWORDS + 10; i++) {
            keywords.record("검색어" + i, 1L);
            keywords.record("검색어" + i, 2L);
        }

        assertThat(keywords.flush()).hasSize(ZeroResultKeywords.MAX_LOGGED);
    }
}
