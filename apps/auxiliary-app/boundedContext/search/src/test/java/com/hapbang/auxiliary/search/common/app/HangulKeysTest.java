package com.hapbang.auxiliary.search.common.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 검색어 입력 도우미의 초성·자모 변환(#973) */
class HangulKeysTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "방송장비 추천 가이드|ㅂㅅㅈㅂ ㅊㅊ ㄱㅇㄷ",
            "꿀팁 따라하기|ㄲㅌ ㄸㄹㅎㄱ",
            "OBS 방송 세팅|obs ㅂㅅ ㅅㅌ",
            "[입문] 프리미어 프로, 기초!|ㅇㅁ ㅍㄹㅁㅇ ㅍㄹ ㄱㅊ",
            "롤 2026 시즌|ㄹ 2026 ㅅㅈ",
            "  침착맨   |ㅊㅊㅁ"
    })
    @DisplayName("초성: 음절은 첫소리만, 영문은 소문자, 숫자는 그대로, 글자·숫자가 아닌 문자는 띄어쓰기로 바꾸고 공백을 줄인다")
    void chosung(String text, String expected) {
        assertThat(HangulKeys.chosung(text)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "방송|ㅂㅏㅇㅅㅗㅇ",
            "닭|ㄷㅏㄹㄱ",
            "과자|ㄱㅗㅏㅈㅏ",
            "의자|ㅇㅡㅣㅈㅏ",
            "값|ㄱㅏㅂㅅ",
            "꿀|ㄲㅜㄹ",
            "있다|ㅇㅣㅆㄷㅏ",
            "OBS 세팅|obs ㅅㅔㅌㅣㅇ",
            "(브이로그)|ㅂㅡㅇㅣㄹㅗㄱㅡ"
    })
    @DisplayName("자모: 겹받침·겹모음은 두 낱자로 나누고, 된소리 ㄲ·ㅆ은 한 낱자로 둔다")
    void jamo(String text, String expected) {
        assertThat(HangulKeys.jamo(text)).isEqualTo(expected);
    }

    @Test
    @DisplayName("한글 입력기가 치는 도중 보내는 모든 글자의 자모는 완성된 낱말 자모의 앞부분이다(자동완성 후보가 끊기지 않는다)")
    void typingStatesArePrefixes() {
        assertPrefixes("방송", List.of("ㅂ", "바", "방", "방ㅅ", "방소", "방송"));
        assertPrefixes("프리미어", List.of("ㅍ", "프", "플", "프리", "프림", "프리미", "프리밍", "프리미어"));
        assertPrefixes("닭", List.of("ㄷ", "다", "달", "닭"));
        assertPrefixes("읽기", List.of("ㅇ", "이", "일", "읽", "읽ㄱ", "읽기"));
        assertPrefixes("과자", List.of("ㄱ", "고", "과", "과ㅈ", "과자"));
        assertPrefixes("의자", List.of("ㅇ", "으", "의", "읮", "의자"));
        assertPrefixes("없다", List.of("ㅇ", "어", "업", "없", "없ㄷ", "없다"));
    }

    private static void assertPrefixes(String word, List<String> states) {
        String full = HangulKeys.jamo(word);
        for (String state : states) {
            assertThat(full).as("%s를 치는 중 %s", word, state).startsWith(HangulKeys.jamo(state));
        }
    }

    @Test
    @DisplayName("입력창에 낱자로 들어온 겹받침·겹모음도 음절 안과 같게 나눈다(초성은 ㄳ → ㄱㅅ)")
    void standaloneCompoundJamo() {
        assertThat(HangulKeys.jamo("ㄺ")).isEqualTo("ㄹㄱ");
        assertThat(HangulKeys.jamo("ㅘ")).isEqualTo("ㅗㅏ");
        assertThat(HangulKeys.chosung("ㄳ")).isEqualTo("ㄱㅅ");
    }

    @Test
    @DisplayName("풀어 쓴 한글(NFD)도 같은 음절로 본다")
    void normalizesToNfc() {
        String decomposed = Normalizer.normalize("방송", Normalizer.Form.NFD);
        assertThat(decomposed).isNotEqualTo("방송");

        assertThat(HangulKeys.chosung(decomposed)).isEqualTo("ㅂㅅ");
        assertThat(HangulKeys.jamo(decomposed)).isEqualTo("ㅂㅏㅇㅅㅗㅇ");
    }

    @Test
    @DisplayName("null은 null, 글자가 남지 않으면 빈 문자열이다")
    void emptyValues() {
        assertThat(HangulKeys.chosung(null)).isNull();
        assertThat(HangulKeys.jamo(null)).isNull();
        assertThat(HangulKeys.chosung(" !? ")).isEmpty();
        assertThat(HangulKeys.jamo("")).isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource(delimiter = '|', value = {
            "ㅂㅅㅈㅂ|true",
            "ㅂㅅㅈㅂ ㅊ|true",
            "ㄲㅌ|true",
            "ㄳ|true",
            "ㅂ송|false",
            "방ㅅ|false",
            "ㅏ|false",
            "ㅂㅅ obs|false",
            "ㅂㅅ1|false",
            "방송|false"
    })
    @DisplayName("초성뿐인 검색어: 낱자 자음과 띄어쓰기로만 되어 있어야 한다")
    void chosungOnly(String keyword, boolean expected) {
        assertThat(HangulKeys.isChosungOnly(keyword)).isEqualTo(expected);
    }

    @Test
    @DisplayName("빈 검색어는 초성뿐이 아니다")
    void blankIsNotChosungOnly() {
        assertThat(HangulKeys.isChosungOnly(null)).isFalse();
        assertThat(HangulKeys.isChosungOnly("   ")).isFalse();
    }
}
