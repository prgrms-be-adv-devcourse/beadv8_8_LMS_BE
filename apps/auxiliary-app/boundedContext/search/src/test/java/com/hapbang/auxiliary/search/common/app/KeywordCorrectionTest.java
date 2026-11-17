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
import com.hapbang.auxiliary.search.common.app.KeywordIndex.Analysis;

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
    @DisplayName("조사·어미만 붙은 말, 동의어로 펼쳐지는 말은 바른 말로 본다")
    void knownByAnalysis() {
        FakeIndex index = new FakeIndex().indexed("방송").analyzed("방송을", "방송").analyzed("을").synonym("배그");

        assertThat(KeywordCorrection.suggest("방송을", index).outcome()).isEqualTo(Outcome.KNOWN);
        assertThat(KeywordCorrection.suggest("을", index).outcome()).isEqualTo(Outcome.KNOWN);
        assertThat(KeywordCorrection.suggest("배그", index).outcome()).isEqualTo(Outcome.KNOWN);
        assertThat(index.candidateCalls).isZero();
    }

    @Test
    @DisplayName("조각이 둘 이상이거나(모두 index에 있어도: 메이풀 → 메이 + 풀), 하나뿐인 조각이 낱말 앞부분이 아니거나 index에 없거나 낱말 그대로면 바른 말이 아니다")
    void notKnownByAnalysis() {
        assertThat(KeywordCorrection.isKnownByAnalysis("발로란투", analysis("발로", "란투"), "발로"::equals)).isFalse();
        assertThat(KeywordCorrection.isKnownByAnalysis("메이풀", analysis("메이", "풀"), word -> true)).isFalse();
        assertThat(KeywordCorrection.isKnownByAnalysis("먹밤", analysis("밤"), word -> true)).isFalse();
        assertThat(KeywordCorrection.isKnownByAnalysis("썸내일을", analysis("썸내일"), word -> false)).isFalse();
        assertThat(KeywordCorrection.isKnownByAnalysis("방송", analysis("방송"), word -> true)).isFalse();
    }

    @Test
    @DisplayName("가장 가까운 후보가 둘 이상이거나, 거리가 0(기호만 다름)이거나 3 이상이면 바꾸지 않는다")
    void tieMeansNoSuggestion() {
        assertThat(KeywordCorrection.closest("가팡", List.of("가방", "가수"))).contains("가방");
        assertThat(KeywordCorrection.closest("가팡", List.of("가방", "가판"))).isEmpty();
        assertThat(KeywordCorrection.closest("가팡", List.of("가팡"))).isEmpty();
        assertThat(KeywordCorrection.closest("가팡", List.of())).isEmpty();
        // 자모 거리 3(가수)은 다른 말이라 후보가 하나뿐이어도 바꾸지 않는다
        assertThat(KeywordCorrection.closest("가팡", List.of("가수"))).isEmpty();
        // 자모로 풀면 기호가 빠져 거리 0이 되는 말은 같은 말로 보고 바꾸지 않는다
        assertThat(KeywordCorrection.closest("c#", List.of("c"))).isEmpty();

        FakeIndex index = new FakeIndex().candidates("가팡", "가방", "가판");
        assertThat(KeywordCorrection.suggest("가팡", index)).isEqualTo(new Result(Outcome.NONE, null));
    }

    @Test
    @DisplayName("문장: 물음표, 5어절 이상, 또는 두 어절 이상이면서 종결·연결·선어말 어미가 있으면 문장이다. 관형형·명사형 어미만 있으면 검색어다. 한 낱말은 품사를 묻지 않는다")
    void sentence() {
        List<String> nouns = List.of("NNG", "NNG");

        assertThat(KeywordCorrection.isSentence("사야 돼요", () -> List.of("VV", "EC", "VV", "EF"))).isTrue();
        assertThat(KeywordCorrection.isSentence("먹방 하려면", () -> List.of("NNG", "XSV", "EC"))).isTrue();
        assertThat(KeywordCorrection.isSentence("가능?", () -> nouns)).isTrue();
        // 물음표가 있으면 한 낱말 오타여도 어절 수·품사와 상관없이 문장이다
        assertThat(KeywordCorrection.isSentence("프리미여?", () -> {
            throw new AssertionError("물음표가 있으면 품사를 묻지 않는다");
        })).isTrue();
        assertThat(KeywordCorrection.isSentence("a b c d e", () -> nouns)).isTrue();
        assertThat(KeywordCorrection.isSentence("방송 장비", () -> nouns)).isFalse();
        assertThat(KeywordCorrection.isSentence("쉬운 프리미여", () -> List.of("VA", "ETM", "NNG", "NNP"))).isFalse();
        assertThat(KeywordCorrection.isSentence("유튜부 시작하기", () -> List.of("NNP", "NNG", "XSV", "ETN"))).isFalse();
        assertThat(KeywordCorrection.isSentence("크로마기", () -> {
            throw new AssertionError("한 낱말은 품사를 묻지 않는다");
        })).isFalse();
    }

    @Test
    @DisplayName("문장이면 낱말을 확인하지 않고 제안하지 않는다")
    void sentenceSkipsCorrection() {
        FakeIndex index = new FakeIndex().partsOfSpeech("NNG", "VV", "EC", "VV", "EF");

        assertThat(KeywordCorrection.suggest("마이크 뭐 사야 돼요", index)).isEqualTo(new Result(Outcome.SENTENCE, null));
        assertThat(index.indexedCalls).isZero();
    }

    private static Analysis analysis(String... tokens) {
        return new Analysis(List.of(tokens), false);
    }

    /** 가짜 index: 정한 낱말만 있고, 정한 후보만 낸다. 분석은 정하지 않으면 낱말 그대로 한 조각이다 */
    private static final class FakeIndex implements KeywordIndex {

        private final Set<String> indexed = new HashSet<>();
        private final Map<String, Analysis> analyzed = new HashMap<>();
        private final Map<String, Set<String>> candidates = new HashMap<>();
        private List<String> partsOfSpeech = List.of("NNG");
        private int indexedCalls;
        private int candidateCalls;

        FakeIndex indexed(String... words) {
            indexed.addAll(List.of(words));
            return this;
        }

        FakeIndex analyzed(String word, String... tokens) {
            analyzed.put(word, analysis(tokens));
            return this;
        }

        FakeIndex synonym(String word) {
            analyzed.put(word, new Analysis(List.of(), true));
            return this;
        }

        FakeIndex candidates(String word, String... values) {
            candidates.put(word, Set.of(values));
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
        public Analysis analyze(String word) {
            return analyzed.getOrDefault(word, analysis(word));
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
    }
}
