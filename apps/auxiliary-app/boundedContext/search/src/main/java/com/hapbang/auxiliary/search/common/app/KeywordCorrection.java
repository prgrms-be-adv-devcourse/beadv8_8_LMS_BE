package com.hapbang.auxiliary.search.common.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 검색어 제안("이 검색어를 찾으셨나요?", #977). 결과 수와 상관없이, 검색어에 index에 없는 낱말이 있으면 가장 가까운 낱말로 바꾼 검색어를 하나 만든다.
 * 결과는 바꾸지 않는다(자동으로 바꿔 검색하지 않음). 대상과 상관없고 ES는 {@link KeywordIndex}로만 묻는다.
 * <ol>
 *     <li><b>문장이면 제안하지 않는다</b>({@link #isSentence}). {@code 마이크 뭐 사야 돼요}의 {@code 돼요}를 고치면 엉뚱한 제안이 된다.</li>
 *     <li><b>낱말마다 index에 있는지 본다</b>. 원문 칸·자모 칸에 낱말로 있거나, 조사·어미만 붙은 말이면({@link #isInflected}) 바른 말이다.</li>
 *     <li><b>없는 낱말은 후보 가운데 가장 가까운 것으로 바꾼다</b>({@link #closest}). 가장 가까운 후보가 둘 이상이면 어느 쪽인지 알 수 없어 바꾸지
 *     않는다.</li>
 *     <li><b>바꾼 검색어로 결과가 있을 때만 제안한다</b>. 제안을 눌렀는데 0건이 되지 않게 한다.</li>
 * </ol>
 * 초성뿐인 검색어는 부르는 쪽이 거른다(초성 검색·자동완성이 맡는다). 영문도 같은 규칙이다(편집 거리가 알파벳 단위다).
 */
public final class KeywordCorrection {

    /** 결과 종류. 지표 태그 값이다 */
    public enum Outcome {
        /** 바꾼 검색어를 제안함 */
        SUGGESTED("suggested"),
        /** 모든 낱말이 index에 있어 고칠 것이 없음 */
        KNOWN("known"),
        /** 모르는 낱말이 있지만 못 고침(후보 없음·가장 가까운 후보가 둘 이상·바꾼 검색어로 결과 없음) */
        NONE("none"),
        /** 문장이라 제안하지 않음 */
        SENTENCE("sentence");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /**
     * @param keyword 제안 검색어. {@link Outcome#SUGGESTED}일 때만 있다
     */
    public record Result(Outcome outcome, String keyword) {

        static Result of(Outcome outcome) {
            return new Result(outcome, null);
        }
    }

    private KeywordCorrection() {
    }

    /**
     * @param keyword 앞뒤·연속 공백을 정리한 검색어(비어 있지 않음, 초성뿐이 아님)
     */
    public static Result suggest(String keyword, KeywordIndex index) {
        if (isSentence(keyword, () -> index.partsOfSpeech(keyword))) {
            return Result.of(Outcome.SENTENCE);
        }
        List<String> words = List.of(keyword.split(" "));
        Set<String> unknown = unknownWords(words, index);
        if (unknown.isEmpty()) {
            return Result.of(Outcome.KNOWN);
        }
        Map<String, Set<String>> candidates = index.candidates(unknown);
        List<String> corrected = new ArrayList<>();
        boolean changed = false;
        for (String word : words) {
            String lower = lower(word);
            Optional<String> replacement = unknown.contains(lower)
                    ? closest(lower, candidates.getOrDefault(lower, Set.of()))
                    : Optional.empty();
            changed |= replacement.isPresent();
            corrected.add(replacement.orElse(word));
        }
        if (!changed) {
            return Result.of(Outcome.NONE);
        }
        String suggestion = String.join(" ", corrected);
        return index.hasResults(suggestion) ? new Result(Outcome.SUGGESTED, suggestion) : Result.of(Outcome.NONE);
    }

    /**
     * 문장형 검색어인지. 물음표가 있으면 문장이다. 그 밖에는 두 어절 이상이면서 nori 품사에 용언(V…)·어미(E…)가 있으면 문장이다. 한 낱말 검색어는
     * 품사를 보지 않는다: nori가 사전에 없는 오타를 용언·어미로 나누는 일이 잦아({@code 크로마기} → {@code 크로마 + 기}) 오타를 문장으로 보게 된다.
     *
     * @param partsOfSpeech 품사가 필요할 때만 부른다(ES 요청)
     */
    static boolean isSentence(String keyword, Supplier<List<String>> partsOfSpeech) {
        if (keyword.indexOf('?') >= 0 || keyword.indexOf('？') >= 0) {
            return true;
        }
        if (keyword.indexOf(' ') < 0) {
            return false;
        }
        return partsOfSpeech.get().stream().anyMatch(tag -> tag.startsWith("V") || tag.startsWith("E"));
    }

    /** index에 없는 낱말(소문자). 조사·어미만 붙은 말은 ES에 한 번 더 물어 뺀다 */
    private static Set<String> unknownWords(List<String> words, KeywordIndex index) {
        Set<String> lowers = new LinkedHashSet<>();
        words.forEach(word -> lowers.add(lower(word)));
        Set<String> indexed = index.indexedWords(lowers);
        Map<String, List<String>> tokens = new LinkedHashMap<>();
        for (String word : lowers) {
            if (!indexed.contains(word)) {
                tokens.put(word, index.analyze(word));
            }
        }
        if (tokens.isEmpty()) {
            return Set.of();
        }
        Set<String> stems = new LinkedHashSet<>();
        tokens.values().stream().filter(t -> t.size() == 1).forEach(t -> stems.add(t.getFirst()));
        Set<String> indexedStems = stems.isEmpty() ? Set.of() : index.indexedWords(stems);
        Set<String> unknown = new LinkedHashSet<>();
        tokens.forEach((word, parts) -> {
            if (!isInflected(word, parts, indexedStems::contains)) {
                unknown.add(word);
            }
        });
        return unknown;
    }

    /**
     * 조사·어미만 붙은 바른 말인지. 분석하면 조사·어미만 남아 조각이 없거나({@code 을}), 조각 하나가 낱말의 앞부분이고 그 조각이 index에 있으면
     * ({@code 방송을} → {@code 방송}) 바른 말이다. 오타는 보통 조각이 여럿이거나 앞부분이 아니다.
     */
    static boolean isInflected(String word, List<String> tokens, Predicate<String> indexed) {
        if (tokens.isEmpty()) {
            return true;
        }
        if (tokens.size() != 1) {
            return false;
        }
        String stem = tokens.getFirst();
        return !stem.equals(word) && word.startsWith(stem) && indexed.test(stem);
    }

    /**
     * 후보 가운데 자모 편집 거리({@link HangulKeys#jamoDistance})가 가장 작은 것. 가장 가까운 후보가 둘 이상이면 비어 있다(헷갈리면 제안하지
     * 않는다). 낱말 자신은 후보로 보지 않는다.
     */
    static Optional<String> closest(String word, Collection<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean tie = false;
        for (String candidate : candidates) {
            if (candidate.equals(word)) {
                continue;
            }
            int distance = HangulKeys.jamoDistance(word, candidate);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
                tie = false;
            } else if (distance == bestDistance && !candidate.equals(best)) {
                tie = true;
            }
        }
        return tie ? Optional.empty() : Optional.ofNullable(best);
    }

    private static String lower(String word) {
        return word.toLowerCase(Locale.ROOT);
    }
}
