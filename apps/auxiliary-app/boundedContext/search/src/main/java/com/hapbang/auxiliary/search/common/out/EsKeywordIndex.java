package com.hapbang.auxiliary.search.common.out;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.hapbang.auxiliary.search.common.app.HangulKeys;
import com.hapbang.auxiliary.search.common.app.KeywordIndex;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.SuggestMode;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.FieldSuggester;
import co.elastic.clients.elasticsearch.core.search.Suggestion;
import co.elastic.clients.elasticsearch.core.search.SuggestSort;
import co.elastic.clients.elasticsearch.indices.analyze.ExplainAnalyzeToken;
import co.elastic.clients.json.JsonData;

/**
 * 검색어 제안(#977)이 묻는 것을 ES로 답한다. 대상과 상관없고 대상은 alias·칸·"보이는 결과" 쿼리만 넘긴다. 요청 하나로 묶을 수 있는 것은 묶는다
 * (낱말 여러 개의 확인은 filter 집계 하나, 후보는 suggest 여러 개를 검색 하나에).
 * <ul>
 *     <li>낱말 확인({@link #indexedWords}): 원문 칸에 그 낱말 그대로, 자모 칸에 그 낱말의 자모가 term으로 있는지. 숨긴 문서도 본다(있는 말을
 *     고치지 않게).</li>
 *     <li>후보({@link #candidates}): term suggester로 원문 칸은 글자 단위(5글자 이상은 편집 2, 그 밖은 1), 자모 칸은 낱말을 자모로 풀어 낱자
 *     단위(편집 2, 첫 낱자는 같아야 함)로 칸마다 5개씩 모은다. 자모 후보는 음절로 되돌린다. term suggester는 숨긴 문서의 낱말도 내므로, <b>보이는
 *     결과가 있는 후보만 남긴다</b>(숨긴 인플루언서의 닉네임 같은 말을 제안하지 않게).</li>
 * </ul>
 */
public class EsKeywordIndex implements KeywordIndex {

    /** 색인 분석기(조사·어미를 뺀다)와 nori tokenizer 이름. 두 대상 index 정의가 같은 이름을 쓴다 */
    static final String INDEX_ANALYZER = "ko_index";
    static final String TOKENIZER = "ko_tokenizer";

    /** 칸마다 받는 후보 수 */
    static final int CANDIDATES_PER_FIELD = 5;

    /** 원문 칸 후보: 이 글자 수 이상이면 편집 2, 아니면 1(짧은 말은 한 글자만 바꿔도 다른 말이 많다) */
    static final int LONG_WORD = 5;

    private final ElasticsearchClient client;
    private final String alias;
    private final List<String> textFields;
    private final List<String> jamoFields;
    private final Function<String, Query> resultsQuery;

    /**
     * @param textFields   원문 칸(가중치 없이)
     * @param jamoFields   자모 칸(가중치 없이)
     * @param resultsQuery 검색어로 찾을 때의 쿼리(보이는 문서 filter 포함). 본 검색과 같아야 한다
     */
    public EsKeywordIndex(ElasticsearchClient client, String alias, List<String> textFields, List<String> jamoFields,
            Function<String, Query> resultsQuery) {
        this.client = client;
        this.alias = alias;
        this.textFields = List.copyOf(textFields);
        this.jamoFields = List.copyOf(jamoFields);
        this.resultsQuery = resultsQuery;
    }

    /** {@code "title^3"} → {@code "title"} */
    public static List<String> withoutBoost(List<String> fields) {
        return fields.stream().map(field -> field.contains("^") ? field.substring(0, field.indexOf('^')) : field).toList();
    }

    @Override
    public Set<String> indexedWords(Collection<String> words) {
        Map<String, Query> filters = new LinkedHashMap<>();
        for (String word : words) {
            filters.put(word, Query.of(q -> q.bool(b -> {
                textFields.forEach(field -> b.should(s -> s.term(t -> t.field(field).value(word))));
                jamoFields.forEach(field -> b.should(s -> s.term(t -> t.field(field).value(HangulKeys.jamo(word)))));
                return b.minimumShouldMatch("1");
            })));
        }
        return matching(filters);
    }

    @Override
    public List<String> analyze(String word) {
        try {
            return client.indices().analyze(a -> a.index(alias).analyzer(INDEX_ANALYZER).text(word)).tokens().stream()
                    .map(token -> token.token()).toList();
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("검색어 제안을 위해 낱말을 분석하지 못했습니다", e);
        }
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
        Map<String, Boolean> jamoSuggester = new HashMap<>();
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
                jamoSuggester.put(name, true);
            }
        }
        Map<String, Set<String>> found = new LinkedHashMap<>();
        words.forEach(word -> found.put(word, new LinkedHashSet<>()));
        try {
            SearchResponse<Void> response = client.search(s -> s.index(alias).size(0)
                    .suggest(sg -> sg.suggesters(suggesters)), Void.class);
            response.suggest().forEach((name, suggestions) -> {
                boolean jamo = jamoSuggester.containsKey(name);
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

    @Override
    public boolean hasResults(String keyword) {
        try {
            return client.count(c -> c.index(alias).query(resultsQuery.apply(keyword))).count() > 0;
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("제안 검색어의 결과를 세지 못했습니다", e);
        }
    }

    /** 보이는 결과가 있는 후보만 남긴다(요청 하나) */
    private Map<String, Set<String>> visibleOnly(Map<String, Set<String>> found) {
        Map<String, Query> filters = new LinkedHashMap<>();
        found.values().forEach(candidates -> candidates.forEach(c -> filters.putIfAbsent(c, resultsQuery.apply(c))));
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

    private static FieldSuggester term(String text, String field, int maxEdits, int prefixLength) {
        return FieldSuggester.of(f -> f.text(text).term(t -> t.field(field).analyzer("whitespace")
                .suggestMode(SuggestMode.Missing).minWordLength(2).prefixLength(prefixLength).maxEdits(maxEdits)
                .size(CANDIDATES_PER_FIELD).sort(SuggestSort.Score)));
    }
}
