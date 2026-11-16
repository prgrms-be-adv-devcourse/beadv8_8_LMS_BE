package com.hapbang.auxiliary.search.common.app;

import java.text.Normalizer;
import java.util.Map;

/**
 * 검색어 입력 도우미(#973)가 쓰는 한글 변환. ES에 한국어 초성·자모 기능이 없어 앱이 색인할 때 원문에서 만들어 넣고, 검색어도 같은 규칙으로 바꿔 비교한다
 * (운영 ES에 플러그인을 더하지 않는다). 대상(강의·인플루언서)과 상관없는 순수 함수다.
 * <ul>
 *     <li><b>초성</b>({@link #chosung}): 음절마다 첫소리만 남긴다. {@code 방송장비 추천} → {@code ㅂㅅㅈㅂ ㅊㅊ}. 초성뿐인 검색어({@code ㅂㅅㅈㅂ})를 찾는 칸이다.</li>
 *     <li><b>자모</b>({@link #jamo}): 음절을 한글 입력기가 글자를 만드는 순서대로 낱자로 푼다. {@code 방송} → {@code ㅂㅏㅇㅅㅗㅇ}. 치는 도중의
 *     입력({@code 방ㅅ}, {@code 방소}, {@code 플})도 원문의 앞부분이 되게 해 자동완성 후보가 끊기지 않게 한다. 그래서 겹받침({@code 닭} →
 *     {@code ㄷㅏㄹㄱ})과 겹모음({@code 과} → {@code ㄱㅗㅏ})은 두 낱자로 나누고(입력기가 두 번 친 키를 합친 것), 된소리({@code ㄲ ㅆ})는 한 키라
 *     그대로 둔다.</li>
 * </ul>
 * 두 변환 모두 영문은 소문자로, 숫자는 그대로 두고, 글자·숫자가 아닌 문자(괄호·쉼표 등)는 띄어쓰기로 바꾼 뒤 연속 공백을 하나로 줄인다. 검색은 띄어쓰기
 * 단위 앞부분으로 맞추므로 {@code [입문]}의 괄호가 낱말 앞에 붙어 있으면 {@code ㅇㅁ}으로 찾지 못하기 때문이다. 입력은 먼저 NFC로 맞춘다(풀어 쓴 한글
 * 자모열이 들어와도 같은 음절로 본다).
 */
public final class HangulKeys {

    private static final int SYLLABLE_FIRST = 0xAC00;
    private static final int SYLLABLE_LAST = 0xD7A3;
    private static final int JUNG_COUNT = 21;
    private static final int JONG_COUNT = 28;

    private static final String[] CHO = {"ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ", "ㄹ", "ㅁ", "ㅂ", "ㅃ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ",
            "ㅋ", "ㅌ", "ㅍ", "ㅎ"};

    /** 가운뎃소리. 겹모음은 입력기에서 두 번 치는 키라 두 낱자로 나눈다 */
    private static final String[] JUNG = {"ㅏ", "ㅐ", "ㅑ", "ㅒ", "ㅓ", "ㅔ", "ㅕ", "ㅖ", "ㅗ", "ㅗㅏ", "ㅗㅐ", "ㅗㅣ", "ㅛ", "ㅜ",
            "ㅜㅓ", "ㅜㅔ", "ㅜㅣ", "ㅠ", "ㅡ", "ㅡㅣ", "ㅣ"};

    /** 끝소리(없음 포함). 겹받침은 두 낱자로 나누고, 된소리 ㄲ·ㅆ은 한 키라 그대로 둔다 */
    private static final String[] JONG = {"", "ㄱ", "ㄲ", "ㄱㅅ", "ㄴ", "ㄴㅈ", "ㄴㅎ", "ㄷ", "ㄹ", "ㄹㄱ", "ㄹㅁ", "ㄹㅂ", "ㄹㅅ",
            "ㄹㅌ", "ㄹㅍ", "ㄹㅎ", "ㅁ", "ㅂ", "ㅂㅅ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"};

    /** 입력창에 낱자로 들어온 겹받침·겹모음(호환 자모)을 음절 안과 같게 나눈다 */
    private static final Map<Character, String> COMPOUND_JAMO = Map.ofEntries(
            Map.entry('ㄳ', "ㄱㅅ"), Map.entry('ㄵ', "ㄴㅈ"), Map.entry('ㄶ', "ㄴㅎ"), Map.entry('ㄺ', "ㄹㄱ"),
            Map.entry('ㄻ', "ㄹㅁ"), Map.entry('ㄼ', "ㄹㅂ"), Map.entry('ㄽ', "ㄹㅅ"), Map.entry('ㄾ', "ㄹㅌ"),
            Map.entry('ㄿ', "ㄹㅍ"), Map.entry('ㅀ', "ㄹㅎ"), Map.entry('ㅄ', "ㅂㅅ"),
            Map.entry('ㅘ', "ㅗㅏ"), Map.entry('ㅙ', "ㅗㅐ"), Map.entry('ㅚ', "ㅗㅣ"), Map.entry('ㅝ', "ㅜㅓ"),
            Map.entry('ㅞ', "ㅜㅔ"), Map.entry('ㅟ', "ㅜㅣ"), Map.entry('ㅢ', "ㅡㅣ"));

    private HangulKeys() {
    }

    /**
     * 초성 문자열. 한글 음절은 첫소리, 낱자로 들어온 겹받침은 두 낱자({@code ㄳ} → {@code ㄱㅅ}), 그 밖은 위 공통 규칙이다.
     *
     * @return 바꾼 문자열. {@code null}이면 {@code null}, 남는 글자가 없으면 빈 문자열
     */
    public static String chosung(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (isSyllable(ch)) {
                out.append(CHO[(ch - SYLLABLE_FIRST) / (JUNG_COUNT * JONG_COUNT)]);
            } else {
                appendOther(out, ch);
            }
        }
        return collapse(out);
    }

    /**
     * 자모 문자열. 한글 음절은 첫소리 + 가운뎃소리 + 끝소리를 입력기 순서대로 낱자로, 낱자로 들어온 겹받침·겹모음도 두 낱자로 나눈다. 그 밖은 위 공통
     * 규칙이다.
     *
     * @return 바꾼 문자열. {@code null}이면 {@code null}, 남는 글자가 없으면 빈 문자열
     */
    public static String jamo(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (isSyllable(ch)) {
                int index = ch - SYLLABLE_FIRST;
                out.append(CHO[index / (JUNG_COUNT * JONG_COUNT)])
                        .append(JUNG[(index % (JUNG_COUNT * JONG_COUNT)) / JONG_COUNT])
                        .append(JONG[index % JONG_COUNT]);
            } else {
                appendOther(out, ch);
            }
        }
        return collapse(out);
    }

    /**
     * 검색어가 초성(낱자 자음)과 띄어쓰기로만 되어 있는지. 낱자 모음·음절·영문·숫자가 하나라도 있으면 아니다. 본 검색과 자동완성이 이때만 초성 칸으로
     * 찾는다(#973).
     */
    public static boolean isChosungOnly(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return false;
        }
        String normalized = Normalizer.normalize(keyword, Normalizer.Form.NFC);
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (!Character.isWhitespace(ch) && !isCompatibilityConsonant(ch)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSyllable(char ch) {
        return ch >= SYLLABLE_FIRST && ch <= SYLLABLE_LAST;
    }

    /** 호환 자모의 자음(ㄱ U+3131 ~ ㅎ U+314E, 겹받침 포함) */
    private static boolean isCompatibilityConsonant(char ch) {
        return ch >= 'ㄱ' && ch <= 'ㅎ';
    }

    private static void appendOther(StringBuilder out, char ch) {
        String compound = COMPOUND_JAMO.get(ch);
        if (compound != null) {
            out.append(compound);
        } else if (Character.isLetterOrDigit(ch)) {
            out.append(Character.toLowerCase(ch));
        } else {
            out.append(' ');
        }
    }

    private static String collapse(StringBuilder out) {
        return out.toString().strip().replaceAll("\\s+", " ");
    }
}
