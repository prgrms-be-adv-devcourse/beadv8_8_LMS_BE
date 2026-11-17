package com.hapbang.auxiliary.search.common.app;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hapbang.auxiliary.search.common.app.KeywordIndex.Analysis;

/**
 * 검색어 제안("이 검색어를 찾으셨나요?", #977). 결과 수와 상관없이, 검색어에 index에 없는 낱말이 있으면 가장 가까운 낱말로 바꾼 검색어를 하나 만든다.
 * 결과는 바꾸지 않는다(자동으로 바꿔 검색하지 않음). 대상과 상관없고 ES는 {@link KeywordIndex}로만 묻는다.
 * <ol>
 *     <li><b>문장이면 제안하지 않는다</b>({@link #isSentence}). {@code 마이크 뭐 사야 돼요}의 {@code 돼요}를 고치면 엉뚱한 제안이 된다.</li>
 *     <li><b>낱말마다 index에 있는지 본다</b>. 원문 칸·자모 칸에 낱말로 있거나, 동의어로 펼쳐지거나, 조사·어미만 붙은 말이면
 *     ({@link #isKnownByAnalysis}) 바른 말이다.</li>
 *     <li><b>없는 낱말은 후보 가운데 가장 가까운 것으로 바꾼다</b>({@link #closest}). 후보는 보이는 문서에 그 낱말 그대로 있는 것만 온다.
 *     자모 거리가 {@value #MAX_DISTANCE}를 넘거나 가장 가까운 후보가 둘 이상이면 바꾸지 않는다. 바꾼 낱말이 보이는 문서에 있으므로 바꾼 검색어도
 *     결과가 있다(제안을 눌렀는데 0건이 되지 않는다).</li>
 * </ol>
 * 초성뿐인 검색어는 하지 않는다(초성 검색·자동완성이 맡는다). 영문도 같은 규칙이다(편집 거리가 알파벳 단위다).
 */
public final class KeywordCorrection {

    private static final Logger log = LoggerFactory.getLogger(KeywordCorrection.class);

    /** 이 어절 수부터는 문장으로 보고 제안하지 않는다(낱말마다 ES에 묻는 요청이 늘지 않게. 오타 검색어는 대개 3어절 이하다) */
    static final int SENTENCE_WORDS = 5;

    /** 바꿀 수 있는 자모 편집 거리 상한. 오타 대부분은 낱자 하나·둘 차이이고, 그보다 먼 말은 다른 말이다 */
    static final int MAX_DISTANCE = 2;

    /** 실패 WARN 간격. 실패가 이어져도 요청마다 남기지 않는다(처음 한 번, 그 뒤 이 간격마다 그 사이 건수와 함께) */
    private static final long WARN_INTERVAL_NANOS = Duration.ofMinutes(10).toNanos();
    private static final AtomicLong nextWarnAt = new AtomicLong(System.nanoTime());
    private static final AtomicLong failuresSinceWarn = new AtomicLong();

    /** 결과 종류. 지표 태그 값이다 */
    public enum Outcome {
        /** 바꾼 검색어를 제안함 */
        SUGGESTED("suggested"),
        /** 모든 낱말이 index에 있어 고칠 것이 없음 */
        KNOWN("known"),
        /** 모르는 낱말이 있지만 못 고침(가까운 후보 없음·가장 가까운 후보가 둘 이상) */
        NONE("none"),
        /** 문장(또는 {@value #SENTENCE_WORDS}어절 이상)이라 제안하지 않음 */
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
     * 검색 API가 첫 페이지 결과를 찾은 뒤 부른다. 제안은 덤이라 무엇이 실패해도 검색 응답을 막지 않는다: 제안 없이(null) 응답한다. 실패는 지표
     * {@code outcome=error}로 세고, WARN은 처음 한 번과 그 뒤 10분마다 그 사이 건수와 함께 남긴다(ES 분석기가 깨진 채 이어지면 요청마다 쌓이지
     * 않게). 결과 종류와 시간을 지표로 남긴다. 검색어는 로그·지표에 남기지 않는다(남의 이름일 수 있다).
     *
     * @param keyword 정리한 검색어. 없거나 초성뿐이면 제안하지 않고 지표도 남기지 않는다(초성 검색·자동완성이 맡는다)
     * @return 제안 검색어. 없으면 null
     */
    public static String suggestOrNull(String keyword, KeywordIndex index, SearchMetrics.Target metrics) {
        if (keyword == null || HangulKeys.isChosungOnly(keyword)) {
            return null;
        }
        long started = System.nanoTime();
        try {
            Result result = suggest(keyword, index);
            metrics.suggested(result.outcome().tag(), Duration.ofNanos(System.nanoTime() - started));
            return result.keyword();
        } catch (RuntimeException e) {
            metrics.suggested("error", Duration.ofNanos(System.nanoTime() - started));
            warnThrottled(metrics.target(), e);
            return null;
        }
    }

    private static void warnThrottled(String target, RuntimeException e) {
        long failures = failuresSinceWarn.incrementAndGet();
        long now = System.nanoTime();
        long next = nextWarnAt.get();
        if (now - next >= 0 && nextWarnAt.compareAndSet(next, now + WARN_INTERVAL_NANOS)) {
            failuresSinceWarn.addAndGet(-failures);
            log.warn("검색어 제안을 만들지 못해 제안 없이 응답한다 target={}, failures={}", target, failures, e);
        }
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
        return changed ? new Result(Outcome.SUGGESTED, String.join(" ", corrected)) : Result.of(Outcome.NONE);
    }

    /**
     * 문장형 검색어인지. 물음표가 있거나 {@value #SENTENCE_WORDS}어절 이상이면 문장이다. 그 밖에는 두 어절 이상이면서 nori 품사에 문장을 끝내거나
     * 잇는 어미(종결 EF·연결 EC·선어말 EP)가 있으면 문장이다({@code 사야 돼요}, {@code 하려면}, {@code 어케 함}의 {@code 게}). 관형형(ETM)·명사형
     * (ETN) 어미만 있으면 검색어다({@code 쉬운 프리미어}, {@code 유튜브 시작하기}). 한 낱말 검색어는 품사를 보지 않는다: nori가 사전에 없는 오타를
     * 어미로 쪼개는 일이 잦아({@code 크로마기} → {@code 크로마 + 기}) 오타를 문장으로 보게 된다.
     *
     * @param partsOfSpeech 품사가 필요할 때만 부른다(ES 요청)
     */
    static boolean isSentence(String keyword, Supplier<List<String>> partsOfSpeech) {
        if (keyword.indexOf('?') >= 0 || keyword.indexOf('？') >= 0) {
            return true;
        }
        int words = keyword.split(" ").length;
        if (words == 1) {
            return false;
        }
        if (words >= SENTENCE_WORDS) {
            return true;
        }
        return partsOfSpeech.get().stream().anyMatch(tag -> tag.equals("EF") || tag.equals("EC") || tag.equals("EP"));
    }

    /** index에 없는 낱말(소문자). 분석이 필요한 낱말은 ES에 한 번 더 물어 뺀다 */
    private static Set<String> unknownWords(List<String> words, KeywordIndex index) {
        Set<String> lowers = new LinkedHashSet<>();
        words.forEach(word -> lowers.add(lower(word)));
        Set<String> indexed = index.indexedWords(lowers);
        Map<String, Analysis> analyses = new LinkedHashMap<>();
        for (String word : lowers) {
            if (!indexed.contains(word)) {
                analyses.put(word, index.analyze(word));
            }
        }
        if (analyses.isEmpty()) {
            return Set.of();
        }
        Set<String> pieces = new LinkedHashSet<>();
        analyses.values().stream().filter(a -> !a.synonym()).forEach(a -> pieces.addAll(a.tokens()));
        Set<String> indexedPieces = pieces.isEmpty() ? Set.of() : index.indexedWords(pieces);
        Set<String> unknown = new LinkedHashSet<>();
        analyses.forEach((word, analysis) -> {
            if (!isKnownByAnalysis(word, analysis, indexedPieces::contains)) {
                unknown.add(word);
            }
        });
        return unknown;
    }

    /**
     * 낱말 그대로는 index에 없지만 분석해 보면 바른 말인지.
     * <ul>
     *     <li>동의어로 펼쳐지는 말({@code 배그} → 배틀그라운드, 운영에서 넣은 {@code 오타 => 바른 말} 포함). 본 검색이 이미 찾는다.</li>
     *     <li>조사·어미만 있어 조각이 없는 말({@code 을})</li>
     *     <li>조각 하나가 낱말 앞부분이고 index에 있는 말: 조사·어미만 붙었다({@code 방송을} → 방송)</li>
     * </ul>
     * 조각이 둘 이상이면 모두 index에 있어도 바른 말로 보지 않는다. nori가 오타를 index에 있는 짧은 조각으로 쪼개는 일이 많다({@code 메이풀} →
     * 메이 + 풀). 붙여 쓴 바른 말({@code 프리미어프로})은 모르는 말이 되지만, 후보가 자모 거리 {@value #MAX_DISTANCE} 안에 없어 제안되지 않는다.
     */
    static boolean isKnownByAnalysis(String word, Analysis analysis, Predicate<String> indexed) {
        if (analysis.synonym()) {
            return true;
        }
        List<String> tokens = analysis.tokens();
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
     * 후보 가운데 자모 편집 거리({@link HangulKeys#jamoDistance})가 가장 작은 것. 거리가 {@value #MAX_DISTANCE}를 넘으면 후보로 보지 않고, 가장
     * 가까운 후보가 둘 이상이면 비어 있다(헷갈리면 제안하지 않는다). 거리 0은 같은 말로 보고 건너뛴다: 자모로 풀면 기호가 빠져 {@code c#}과
     * {@code c}가 같아진다.
     */
    static Optional<String> closest(String word, Collection<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean tie = false;
        for (String candidate : candidates) {
            int distance = HangulKeys.jamoDistance(word, candidate);
            if (distance == 0 || distance > MAX_DISTANCE) {
                continue;
            }
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
