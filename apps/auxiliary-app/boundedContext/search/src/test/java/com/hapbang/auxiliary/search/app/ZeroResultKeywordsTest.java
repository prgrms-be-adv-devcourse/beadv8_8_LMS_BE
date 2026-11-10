package com.hapbang.auxiliary.search.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 결과 0건 검색어 모음(#771 Q9) */
class ZeroResultKeywordsTest {

    private final ZeroResultKeywords keywords = new ZeroResultKeywords();

    @Test
    @DisplayName("공백·대소문자를 맞춰 세고, 많이 나온 순으로 돌려준 뒤 비운다")
    void countsAndFlushes() {
        keywords.record("롤토체스");
        keywords.record("  롤토체스 ");
        keywords.record("TFT");
        keywords.record("tft");
        keywords.record("tft");

        assertThat(keywords.flush()).containsExactly(java.util.Map.entry("tft", 3L), java.util.Map.entry("롤토체스", 2L));
        assertThat(keywords.flush()).isEmpty();
    }

    @Test
    @DisplayName("한 주기에 서로 다른 검색어는 상한까지만 세고, 로그에는 상위만 남긴다")
    void limits() {
        for (int i = 0; i < ZeroResultKeywords.MAX_KEYWORDS + 10; i++) {
            keywords.record("검색어" + i);
        }

        assertThat(keywords.flush()).hasSize(ZeroResultKeywords.MAX_LOGGED);
    }
}
