package com.hapbang.auxiliary.search.common.out;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hapbang.auxiliary.search.common.app.HangulKeys;
import com.hapbang.auxiliary.search.common.app.KeywordIndex;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.SuggestMode;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.FieldSuggester;
import co.elastic.clients.elasticsearch.core.search.Suggestion;
import co.elastic.clients.elasticsearch.core.search.SuggestSort;
import co.elastic.clients.elasticsearch.indices.analyze.AnalyzeToken;
import co.elastic.clients.elasticsearch.indices.analyze.ExplainAnalyzeToken;
import co.elastic.clients.json.JsonData;

/**
 * 검색어 제안(#977)이 묻는 것을 ES로 답한다. 대상과 상관없고 대상은 alias·칸·"보이는 문서" filter만 넘긴다. 요청 하나로 묶을 수 있는 것은
 * 묶는다(낱말 여러 개의 확인은 filter 집계 하나, 후보는 suggest 여러 개를 검색 하나에).
 * <ul>
 *     <li>낱말 확인({@link #indexedWords}): 원문 칸에 그 낱말 그대로, 자모 칸에 그 낱말의 자모가 term으로 있는지. 숨긴 문서도 본다(있는 말을
 *     고치지 않게).</li>
 *     <li>분석({@link #analyze}): 검색 분석기(동의어 포함)로 나눈다. 동의어 판정은 {@link #coveredBySynonym}.</li>
 *     <li>후보({@link #candidates}): term suggester로 원문 칸은 글자 단위(5글자 이상은 편집 2, 그 밖은 1), 자모 칸은 낱말을 자모로 풀어 낱자
 *     단위(편집 2, 첫 낱자는 같아야 함)로 칸마다 5개씩 모은다. 자모 후보는 음절로 되돌린다. term suggester는 숨긴 문서의 낱말도 내므로, <b>보이는
 *     문서에 그 낱말 그대로(term) 있고, 그 낱말로 찾으면 보이는 문서가 나오는 후보만 남긴다</b>({@link #visibleOnly}). 본 검색처럼 조각 하나만
 *     맞아도 되는 쿼리로만 확인하면 숨긴 닉네임({@code 홍길동})이 보이는 다른 문서의 조각({@code 길동})으로 통과해 드러난다.</li>
 * </ul>
 */
public class EsKeywordIndex implements KeywordIndex {

    /** 검색 분석기(색인 분석기 + 동의어)와 nori tokenizer 이름. 두 대상 index 정의가 같은 이름을 쓴다 */
    static final String SEARCH_ANALYZER = "ko_search";
    static final String TOKENIZER = "ko_tokenizer";

    /** 칸마다 받는 후보 수 */
    static final int CANDIDATES_PER_FIELD = 5;

    /** 원문 칸 후보: 이 글자 수 이상이면 편집 2, 아니면 1(짧은 말은 한 글자만 바꿔도 다른 말이 많다) */
    static final int LONG_WORD = 5;

    /** 동의어로 펼친 조각의 type */
    private static final String SYNONYM = "SYNONYM";

    private final ElasticsearchClient client;
    private final String alias;
    private final List<String> textFields;
    private final List<String> jamoFields;
    private final Query visible;

    /**
     * @param textFields 원문 칸(가중치 없이)
     * @param jamoFields 자모 칸(가중치 없이)
     * @param visible    검색에 보이는 문서 filter. 본 검색과 같아야 한다
     */
    public EsKeywordIndex(ElasticsearchClient client, String alias, List<String> textFields, List<String> jamoFields,
            Query visible) {
        this.client = client;
        this.alias = alias;
        this.textFields = List.copyOf(textFields);
        this.jamoFields = List.copyOf(jamoFields);
        this.visible = visible;
    }

    /** {@code "title^3"} → {@code "title"} */
    public static List<String> withoutBoost(List<String> fields) {
        return fields.stream().map(field -> field.contains("^") ? field.substring(0, field.indexOf('^')) : field).toList();
    }

    @Override
    public Set<String> indexedWords(Collection<String> words) {
        Map<String, Query> filters = new LinkedHashMap<>();
        words.forEach(word -> filters.put(word, hasTerm(word)));
        return matching(filters);
    }

    @Override
    public Analysis analyze(String word) {
        try {
            List<AnalyzeToken> all = client.indices().analyze(a -> a.index(alias).analyzer(SEARCH_ANALYZER).text(word))
                    .tokens();
            List<AnalyzeToken> synonyms = all.stream().filter(token -> SYNONYM.equals(token.type())).toList();
            List<AnalyzeToken> words = all.stream().filter(token -> !SYNONYM.equals(token.type())).toList();
            return new Analysis(words.stream().map(AnalyzeToken::token).toList(), coveredBySynonym(synonyms, words));
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("검색어 제안을 위해 낱말을 분석하지 못했습니다", e);
        }
    }

    /**
     * 낱말이 동의어로 펼쳐지는지: 낱말 맨 앞에서 시작하는 동의어 조각이 있고, 동의어가 아닌 조각이 모두 동의어 조각 안에 있다. 조사·어미는 분석에서
     * 빠지므로 {@code 배그를}도 동의어다. 동의어 밖에 조각이 남으면({@code 메이플장임}의 {@code 장}·{@code 임}) 아니다.
     */
    static boolean coveredBySynonym(List<AnalyzeToken> synonyms, List<AnalyzeToken> words) {
        if (synonyms.stream().noneMatch(token -> token.startOffset() == 0)) {
            return false;
        }
        return words.stream().allMatch(word -> synonyms.stream().anyMatch(synonym ->
                synonym.startOffset() <= word.startOffset() && word.endOffset() <= synonym.endOffset()));
    }

    @Override
    public List<String> partsOfSpeech(String keyword) {
        try {
            List<String> tags = new ArrayList<>();
            for (ExplainAnalyzeToken token : client.indices().analyze(a -> a.index(alias)
                    .tokenizer(t -> t.name(TOKENIZER)).text(keyword).explain(true)).detail().tokenizer().tokens()) {
                // attributes로 leftPOS·rightPOS만 고르면 ES가 bytes 등을 빼서 Java client가 응답을 읽지 못한다. 모두 받는다
                for (String attribute : List.of("leftPOS", "rightPOS")) {
                    JsonData value = token.attributes().get(attribute);
                    if (value != null) {
                        String tag = value.to(String.class);
                        tags.add(tag.contains("(") ? tag.substring(0, tag.indexOf('(')) : tag);
                    }
                }
            }
            return tags;
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("검색어 제안을 위해 품사를 구하지 못했습니다", e);
        }
    }

    @Override
    public Map<String, Set<String>> candidates(Collection<String> words) {
        Map<String, FieldSuggester> suggesters = new LinkedHashMap<>();
        Map<String, String> wordOf = new HashMap<>();
        Set<String> jamoSuggesters = new HashSet<>();
        int i = 0;
        for (String word : words) {
            int maxEdits = word.length() >= LONG_WORD ? 2 : 1;
            for (String field : textFields) {
                String name = "s" + i++;
                suggesters.put(name, term(word, field, maxEdits, 0));
                wordOf.put(name, word);
            }
            String jamo = HangulKeys.jamo(word);
            if (jamo.isEmpty() || jamo.contains(" ")) {
                continue; // 자모로 풀면 띄어쓰기가 생기는 말(문장부호 포함)은 자모 칸 낱말과 맞을 수 없다
            }
            for (String field : jamoFields) {
                String name = "s" + i++;
                suggesters.put(name, term(jamo, field, 2, 1));
                wordOf.put(name, word);
                jamoSuggesters.add(name);
            }
        }
        Map<String, Set<String>> found = new LinkedHashMap<>();
        words.forEach(word -> found.put(word, new LinkedHashSet<>()));
        try {
            SearchResponse<Void> response = client.search(s -> s.index(alias).size(0)
                    .suggest(sg -> sg.suggesters(suggesters)), Void.class);
            response.suggest().forEach((name, suggestions) -> {
                boolean jamo = jamoSuggesters.contains(name);
                for (Suggestion<Void> suggestion : suggestions) {
                    suggestion.term().options().forEach(option ->
                            found.get(wordOf.get(name)).add(jamo ? HangulKeys.compose(option.text()) : option.text()));
                }
            });
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("검색어 제안 후보를 찾지 못했습니다", e);
        }
        return visibleOnly(found);
    }

    /**
     * 보이는 문서에서 쓸 수 있는 후보만 남긴다(요청 하나). 둘 다 맞아야 한다.
     * <ul>
     *     <li>보이는 문서에 그 낱말 그대로(term) 있다: 숨긴 닉네임이 보이는 다른 문서의 조각으로 통과하지 않게</li>
     *     <li>그 낱말을 검색 분석기로 찾으면 보이는 문서에 모든 조각이 맞는다: 자모 칸은 제목 낱말을 분석 없이 담아, 본 검색이 버리는 말({@code 가장}
     *     같은 부사)도 후보가 된다. 그 말을 제안하면 눌렀을 때 0건이다</li>
     * </ul>
     */
    private Map<String, Set<String>> visibleOnly(Map<String, Set<String>> found) {
        Map<String, Query> filters = new LinkedHashMap<>();
        found.values().forEach(candidates -> candidates.forEach(c -> filters.putIfAbsent(c, Query.of(q -> q.bool(b -> b
                .filter(visible).filter(hasTerm(c))
                .filter(f -> f.multiMatch(m -> m.query(c).fields(textFields).operator(Operator.And))))))));
        if (filters.isEmpty()) {
            return found;
        }
        Set<String> visible = matching(filters);
        Map<String, Set<String>> out = new LinkedHashMap<>();
        found.forEach((word, candidates) -> out.put(word,
                candidates.stream().filter(visible::contains).collect(LinkedHashSet::new, Set::add, Set::addAll)));
        return out;
    }

    /** 쿼리마다 맞는 문서가 있는지 filter 집계 하나로 센다. 맞는 문서가 있는 키만 돌려준다 */
    private Set<String> matching(Map<String, Query> filters) {
        Map<String, Aggregation> aggregations = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(filters.keySet());
        for (int i = 0; i < keys.size(); i++) {
            Query filter = filters.get(keys.get(i));
            aggregations.put("f" + i, Aggregation.of(a -> a.filter(filter)));
        }
        try {
            SearchResponse<Void> response = client.search(s -> s.index(alias).size(0).trackTotalHits(t -> t.enabled(false))
                    .aggregations(aggregations), Void.class);
            Set<String> out = new LinkedHashSet<>();
            for (int i = 0; i < keys.size(); i++) {
                if (response.aggregations().get("f" + i).filter().docCount() > 0) {
                    out.add(keys.get(i));
                }
            }
            return out;
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("검색어 제안을 위해 낱말을 확인하지 못했습니다", e);
        }
    }

    /** 원문 칸에 그 낱말이, 또는 자모 칸에 그 낱말의 자모가 term으로 있는 문서 */
    private Query hasTerm(String word) {
        String jamo = HangulKeys.jamo(word);
        return Query.of(q -> q.bool(b -> {
            textFields.forEach(field -> b.should(s -> s.term(t -> t.field(field).value(word))));
            jamoFields.forEach(field -> b.should(s -> s.term(t -> t.field(field).value(jamo))));
            return b.minimumShouldMatch("1");
        }));
    }

    private static FieldSuggester term(String text, String field, int maxEdits, int prefixLength) {
        return FieldSuggester.of(f -> f.text(text).term(t -> t.field(field).analyzer("whitespace")
                .suggestMode(SuggestMode.Missing).minWordLength(2).prefixLength(prefixLength).maxEdits(maxEdits)
                .size(CANDIDATES_PER_FIELD).sort(SuggestSort.Score)));
    }
}
