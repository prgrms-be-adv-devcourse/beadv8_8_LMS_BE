package com.hapbang.auxiliary.search.common.app;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
 * <p>
 * <b>띄어쓰기</b>: 검색은 띄어쓰기 단위로 비교하므로, 색인할 때 띄어쓰기를 뺀 값도 함께 넣고({@link #withCompact}) 검색어도 띄어쓰기를 뺀 것으로 한 번
 * 더 찾는다. 한국어는 띄어쓰기가 사람마다 달라 {@code ㅂㅅㅈㅂㅊㅊ}·{@code ㅂㅅ ㅈㅂ}처럼 제목과 다르게 쳐도 맞게 한다.
 */
public final class HangulKeys {

    /** 연속 공백. 자동완성 요청·색인 쓰기마다 불려 미리 컴파일해 둔다 */
    private static final Pattern SPACES = Pattern.compile("\\s+");

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
     * 초성 칸에 넣을 값({@link #withCompact}({@link #chosung})). 색인 쓰기와 칸 맞추기 비교가 모두 이것만 써서 둘이 어긋나지 않게 한다.
     */
    public static List<String> chosungKeys(String text) {
        return withCompact(chosung(text));
    }

    /**
     * 자모 칸에 넣을 값({@link #withCompact}({@link #jamo})). 색인 쓰기와 칸 맞추기 비교가 모두 이것만 쓴다.
     */
    public static List<String> jamoKeys(String text) {
        return withCompact(jamo(text));
    }

    /**
     * 초성·자모 칸에 넣을 값: 그 값과, 띄어쓰기가 있으면 띄어쓰기를 뺀 값. {@code ㅂㅅㅈㅂ ㅊㅊ} → {@code [ㅂㅅㅈㅂ ㅊㅊ, ㅂㅅㅈㅂㅊㅊ]}.
     *
     * @param key {@link #chosung}·{@link #jamo}로 만든 값
     * @return {@code null}이면 {@code null}, 비었으면 빈 목록
     */
    public static List<String> withCompact(String key) {
        if (key == null) {
            return null;
        }
        if (key.isEmpty()) {
            return List.of();
        }
        String compact = key.replace(" ", "");
        return compact.equals(key) ? List.of(key) : List.of(key, compact);
    }

    /**
     * 검색어가 초성(낱자 자음)과 띄어쓰기로만 되어 있는지. 낱자 모음·음절·영문·숫자가 하나라도 있으면 아니다. 본 검색과 자동완성이 이때만 초성 칸으로
     * 찾는다(#973). 글자·숫자가 아닌 문자(쉼표, 줄바꿈 없는 공백 NBSP 등)는 {@link #chosung}처럼 띄어쓰기로 본다. 모바일 입력·붙여넣기로 섞여 들어와도
     * 초성 검색어로 본다({@code ㅂㅅ,}). 초성이 하나도 없으면 아니다.
     */
    public static boolean isChosungOnly(String keyword) {
        if (keyword == null) {
            return false;
        }
        String normalized = Normalizer.normalize(keyword, Normalizer.Form.NFC);
        boolean consonant = false;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (isCompatibilityConsonant(ch)) {
                consonant = true;
            } else if (Character.isLetterOrDigit(ch)) {
                return false;
            }
        }
        return consonant;
    }

    /**
     * {@link #jamo}로 푼 낱자를 한글 입력기 규칙대로 다시 음절로 묶는다(#977 검색어 제안이 자모 칸에서 찾은 낱말을 되돌린다).
     * <ul>
     *     <li>첫소리 뒤에 모음이 오면 음절을 연다. 두 모음이 겹모음이 되면({@code ㅗㅏ} → {@code ㅘ}) 하나로 묶는다.</li>
     *     <li>뒤따르는 자음은 받침이 된다. 겹받침이 되는 두 자음({@code ㄹㄱ})은 하나로 묶는다. 다만 그 자음 바로 뒤에 모음이 오면 다음 음절의 첫소리로
     *     넘긴다({@code ㄷㅏㄹㄱㅇㅡㄹ} → {@code 닭을}, {@code ㅇㅣㄹㄱㅣ} → {@code 읽기}가 아니라 {@code 일기}).</li>
     *     <li>음절이 되지 못하는 낱자·영문·숫자·띄어쓰기는 그대로 둔다.</li>
     * </ul>
     *
     * @return 묶은 문자열. {@code null}이면 {@code null}
     */
    public static String compose(String jamo) {
        if (jamo == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        int length = jamo.length();
        int i = 0;
        while (i < length) {
            int cho = indexOf(CHO, jamo.substring(i, i + 1));
            if (cho < 0 || i + 1 >= length || !isSimpleVowel(jamo.charAt(i + 1))) {
                out.append(jamo.charAt(i));
                i++;
                continue;
            }
            int next = i + 2;
            String vowel = jamo.substring(i + 1, i + 2);
            if (next < length && indexOf(JUNG, vowel + jamo.charAt(next)) >= 0) {
                vowel += jamo.charAt(next);
                next++;
            }
            String jong = jong(jamo, next);
            out.append((char) (SYLLABLE_FIRST + (cho * JUNG_COUNT + indexOf(JUNG, vowel)) * JONG_COUNT + indexOf(JONG, jong)));
            i = next + jong.length();
        }
        return out.toString();
    }

    /**
     * 두 말을 {@link #jamo}로 풀어 낱자 몇 개를 넣고·빼고·바꿔야 같아지는지(편집 거리). 한글 오타는 글자보다 낱자가 가깝다: {@code 가팡}은
     * {@code 가방}과 1({@code ㅍ}→{@code ㅂ}), {@code 가수}와 3이다. 글자 단위로는 둘 다 1이라 가를 수 없다. 영문은 알파벳 단위다.
     */
    public static int jamoDistance(String a, String b) {
        String left = jamo(a);
        String right = jamo(b);
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int replace = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(replace, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    /** {@code from}부터 받침이 될 자음(겹받침이면 둘, 없으면 빈 문자열). 자음 바로 뒤가 모음이면 그 자음은 다음 음절의 첫소리다 */
    private static String jong(String jamo, int from) {
        for (int size = 2; size >= 1; size--) {
            int end = from + size;
            if (end > jamo.length() || indexOf(JONG, jamo.substring(from, end)) <= 0) {
                continue;
            }
            if (end < jamo.length() && isSimpleVowel(jamo.charAt(end))) {
                continue;
            }
            return jamo.substring(from, end);
        }
        return "";
    }

    private static int indexOf(String[] table, String value) {
        for (int i = 0; i < table.length; i++) {
            if (table[i].equals(value)) {
                return i;
            }
        }
        return -1;
    }

    /** {@link #jamo}가 내는 홑모음(겹모음은 이 둘로 나뉘어 있다) */
    private static boolean isSimpleVowel(char ch) {
        return "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅛㅜㅠㅡㅣ".indexOf(ch) >= 0;
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
        return SPACES.matcher(out.toString().strip()).replaceAll(" ");
    }
}
