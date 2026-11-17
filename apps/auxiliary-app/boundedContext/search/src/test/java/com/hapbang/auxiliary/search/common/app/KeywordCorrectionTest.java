package com.hapbang.auxiliary.search.common.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.common.app.KeywordCorrection.Outcome;
import com.hapbang.auxiliary.search.common.app.KeywordCorrection.Result;

/** 검색어 제안 규칙(#977). ES 대신 가짜 index로 규칙만 본다(실제 nori 결과는 {@code EsKeywordIndexTest}) */
class KeywordCorrectionTest {

    @Test
    @DisplayName("index에 없는 낱말을 자모로 가장 가까운 후보로 바꾸고, 있는 낱말·대소문자는 그대로 둔다")
    void suggestsClosest() {
        FakeIndex index = new FakeIndex()
                .indexed("컷편집", "obs")
                .candidates("프리미여", "프리미어", "프리미엄")
                .candidates("studoi", "studio");

        assertThat(KeywordCorrection.suggest("프리미여 컷편집", index))
                .isEqualTo(new Result(Outcome.SUGGESTED, "프리미어 컷편집"));
        assertThat(KeywordCorrection.suggest("OBS studoi", index))
                .isEqualTo(new Result(Outcome.SUGGESTED, "OBS studio"));
    }

    @Test
    @DisplayName("모든 낱말이 index에 있으면 고칠 것이 없다(후보를 묻지 않는다)")
    void allKnown() {
        FakeIndex index = new FakeIndex().indexed("방송", "장비");

        assertThat(KeywordCorrection.suggest("방송 장비", index)).isEqualTo(new Result(Outcome.KNOWN, null));
        assertThat(index.candidateCalls).isZero();
    }

    @Test
    @DisplayName("조사·어미만 붙은 말은 바른 말로 본다(방송을 → 방송, 을 → 조각 없음)")
    void inflectedWordsAreKnown() {
        FakeIndex index = new FakeIndex().indexed("방송").analyzed("방송을", "방송").analyzed("을");

        assertThat(KeywordCorrection.suggest("방송을", index).outcome()).isEqualTo(Outcome.KNOWN);
        assertThat(KeywordCorrection.suggest("을", index).outcome()).isEqualTo(Outcome.KNOWN);
    }

    @Test
    @DisplayName("분석 조각이 낱말 앞부분이 아니거나, index에 없거나, 여럿이면 바른 말이 아니다")
    void notInflected() {
        assertThat(KeywordCorrection.isInflected("발로란투", List.of("발로", "란투"), word -> true)).isFalse();
        assertThat(KeywordCorrection.isInflected("먹밤", List.of("밤"), word -> true)).isFalse();
        assertThat(KeywordCorrection.isInflected("썸내일을", List.of("썸내일"), word -> false)).isFalse();
        assertThat(KeywordCorrection.isInflected("방송", List.of("방송"), word -> true)).isFalse();
    }

    @Test
    @DisplayName("가장 가까운 후보가 둘 이상이면(헷갈리면) 바꾸지 않는다")
    void tieMeansNoSuggestion() {
        assertThat(KeywordCorrection.closest("가팡", List.of("가방", "가수"))).contains("가방");
        assertThat(KeywordCorrection.closest("가팡", List.of("가방", "가판"))).isEmpty();
        assertThat(KeywordCorrection.closest("가팡", List.of("가팡"))).isEmpty();
        assertThat(KeywordCorrection.closest("가팡", List.of())).isEmpty();

        FakeIndex index = new FakeIndex().candidates("가팡", "가방", "가판");
        assertThat(KeywordCorrection.suggest("가팡", index)).isEqualTo(new Result(Outcome.NONE, null));
    }

    @Test
    @DisplayName("바꾼 검색어로 결과가 없으면 제안하지 않는다")
    void suggestionWithoutResults() {
        FakeIndex index = new FakeIndex().candidates("프리미여", "프리미어").noResults("프리미어");

        assertThat(KeywordCorrection.suggest("프리미여", index).outcome()).isEqualTo(Outcome.NONE);
    }

    @Test
    @DisplayName("문장: 물음표가 있거나, 두 어절 이상이면서 용언·어미가 있으면 문장이다. 한 낱말은 품사를 묻지 않는다")
    void sentence() {
        List<String> verb = List.of("NNG", "VV", "EF");
        List<String> nouns = List.of("NNG", "NNG");

        assertThat(KeywordCorrection.isSentence("마이크 뭐 사야 돼요", () -> verb)).isTrue();
        assertThat(KeywordCorrection.isSentence("가능?", () -> nouns)).isTrue();
        assertThat(KeywordCorrection.isSentence("방송 장비", () -> nouns)).isFalse();
        assertThat(KeywordCorrection.isSentence("크로마기", () -> {
            throw new AssertionError("한 낱말은 품사를 묻지 않는다");
        })).isFalse();
    }

    @Test
    @DisplayName("문장이면 낱말을 확인하지 않고 제안하지 않는다")
    void sentenceSkipsCorrection() {
        FakeIndex index = new FakeIndex().partsOfSpeech("NNG", "VV", "EF");

        assertThat(KeywordCorrection.suggest("마이크 뭐 사야 돼요", index)).isEqualTo(new Result(Outcome.SENTENCE, null));
        assertThat(index.indexedCalls).isZero();
    }

    /** 가짜 index: 정한 낱말만 있고, 정한 후보만 내고, 결과는 {@link #noResults}로 뺀 검색어 말고 다 있다 */
    private static final class FakeIndex implements KeywordIndex {

        private final Set<String> indexed = new HashSet<>();
        private final Map<String, List<String>> analyzed = new HashMap<>();
        private final Map<String, Set<String>> candidates = new HashMap<>();
        private final Set<String> noResults = new HashSet<>();
        private List<String> partsOfSpeech = List.of("NNG");
        private int indexedCalls;
        private int candidateCalls;

        FakeIndex indexed(String... words) {
            indexed.addAll(List.of(words));
            return this;
        }

        FakeIndex analyzed(String word, String... tokens) {
            analyzed.put(word, List.of(tokens));
            return this;
        }

        FakeIndex candidates(String word, String... values) {
            candidates.put(word, Set.of(values));
            return this;
        }

        FakeIndex noResults(String keyword) {
            noResults.add(keyword);
            return this;
        }

        FakeIndex partsOfSpeech(String... tags) {
            partsOfSpeech = List.of(tags);
            return this;
        }

        @Override
        public Set<String> indexedWords(Collection<String> words) {
            indexedCalls++;
            return words.stream().filter(indexed::contains).collect(Collectors.toSet());
        }

        @Override
        public List<String> analyze(String word) {
            return analyzed.getOrDefault(word, List.of(word));
        }

        @Override
        public List<String> partsOfSpeech(String keyword) {
            return new ArrayList<>(partsOfSpeech);
        }

        @Override
        public Map<String, Set<String>> candidates(Collection<String> words) {
            candidateCalls++;
            Map<String, Set<String>> out = new HashMap<>();
            words.forEach(word -> out.put(word, candidates.getOrDefault(word, Set.of())));
            return out;
        }

        @Override
        public boolean hasResults(String keyword) {
            return !noResults.contains(keyword);
        }
    }
}
