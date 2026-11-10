package com.hapbang.auxiliary.search.out;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.app.InfluencerSearchCondition;
import com.hapbang.auxiliary.search.app.InfluencerSort;
import com.hapbang.auxiliary.search.app.dto.InfluencerSearchItem;
import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;
import com.hapbang.auxiliary.search.config.SearchElasticsearchConfig;
import com.hapbang.auxiliary.search.config.SearchRankingProperties;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.ScriptSortType;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;

/**
 * 인플루언서 검색 쿼리(#771). 시험 코드 {@code GoldenQueryTest}의 쿼리를 옮겼다.
 * <ul>
 *     <li>filter: 숨기지 않은 문서 + 인증 여부·팔로워·평점 최소값 + 분야(하나라도 가진 사람, OR)</li>
 *     <li>must(검색어): {@code multi_match} best_fields, 닉네임 ×3, 채널 이름 ×3, 분야 이름 ×2, 자기소개 ×1, tie_breaker 0.3.
 *     검색 분석기가 동의어를 펼친다</li>
 *     <li>should(분야): 고른 분야마다 term. 많이 맞을수록 점수가 커진다(filter가 이미 걸렀으므로 순서에만 쓰인다)</li>
 *     <li>검색어가 있으면 {@code script_score}로 점수 × (1 + a·log10(1+팔로워)) × (인증이면 v)</li>
 * </ul>
 * 순서: 기본은 검색어나 분야가 있으면 점수 → 팔로워, 둘 다 없으면 팔로워. {@code followers}는 팔로워 → 점수. {@code rating}은 베이지안
 * 평균 (v×R + m×C)/(v+m) → 팔로워.
 */
@Component
@ConditionalOnSearchEnabled
public class InfluencerSearcher {

    /** 검색어를 찾는 필드와 가중치 */
    static final List<String> KEYWORD_FIELDS = List.of("nickname^3", "channelNames^3", "contentCategories.name^2", "bio^1");

    static final String FOLLOWER_BOOST_SCRIPT = """
            double followers = doc['followerCount'].size() == 0 ? 0 : doc['followerCount'].value;
            double score = _score * (1 + params.a * Math.log10(1 + followers));
            return doc['verified'].size() != 0 && doc['verified'].value ? score * params.v : score;
            """;

    static final String BAYESIAN_RATING_SCRIPT = """
            double n = doc['ratingCount'].size() == 0 ? 0 : doc['ratingCount'].value;
            double r = doc['ratingAverage'].size() == 0 ? 0 : doc['ratingAverage'].value;
            return (n * r + params.m * params.c) / (n + params.m);
            """;

    private final ElasticsearchClient client;
    private final SearchRankingProperties ranking;

    public InfluencerSearcher(@Qualifier(SearchElasticsearchConfig.CLIENT) ElasticsearchClient client,
            SearchRankingProperties ranking) {
        this.client = client;
        this.ranking = ranking;
    }

    /**
     * @param ratingBaseline 평점순 베이지안 평균의 전체 평균 C
     * @param maxTotal       전체 건수를 이 값까지만 센다(페이지를 넘길 수 있는 범위)
     */
    @SuppressWarnings("rawtypes")
    public SearchResult search(InfluencerSearchCondition condition, double ratingBaseline, int maxTotal) {
        try {
            SearchResponse<Map> response = client.search(s -> s.index(InfluencerIndexDefinition.ALIAS)
                    .query(query(condition))
                    .sort(sort(condition, ratingBaseline))
                    .from((int) condition.offset())
                    .size(condition.size())
                    .trackTotalHits(t -> t.count(maxTotal)), Map.class);
            List<InfluencerSearchItem> items = response.hits().hits().stream().map(InfluencerSearcher::item).toList();
            long total = response.hits().total() == null ? items.size() : response.hits().total().value();
            return new SearchResult(Math.min(total, maxTotal), items);
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("인플루언서를 검색하지 못했습니다", e);
        }
    }

    /** 보이는 문서의 출연 평점 전체 평균(리뷰 수 가중). 리뷰가 하나도 없으면 null */
    public Double ratingBaseline() {
        try {
            return client.search(s -> s.index(InfluencerIndexDefinition.ALIAS).size(0)
                    .query(q -> q.term(t -> t.field("hidden").value(false)))
                    .aggregations("c", a -> a.weightedAvg(w -> w.value(v -> v.field("ratingAverage"))
                            .weight(v -> v.field("ratingCount")))), Void.class)
                    .aggregations().get("c").weightedAvg().value();
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("출연 평점 전체 평균을 구하지 못했습니다", e);
        }
    }

    Query query(InfluencerSearchCondition condition) {
        BoolQuery.Builder bool = new BoolQuery.Builder();
        bool.filter(f -> f.term(t -> t.field("hidden").value(false)));
        if (condition.verified() != null) {
            bool.filter(f -> f.term(t -> t.field("verified").value(condition.verified())));
        }
        if (condition.minFollowers() != null) {
            bool.filter(f -> f.range(r -> r.number(n -> n.field("followerCount").gte((double) condition.minFollowers()))));
        }
        if (condition.minRating() != null) {
            bool.filter(f -> f.range(r -> r.number(n -> n.field("ratingAverage").gte(condition.minRating()))));
        }
        if (!condition.contentCategories().isEmpty()) {
            List<FieldValue> codes = condition.contentCategories().stream().map(FieldValue::of).toList();
            bool.filter(f -> f.terms(t -> t.field("contentCategories.code").terms(v -> v.value(codes))));
            condition.contentCategories().forEach(code ->
                    bool.should(s -> s.term(t -> t.field("contentCategories.code").value(code))));
        }
        if (condition.keyword() == null) {
            return bool.build()._toQuery();
        }
        bool.must(m -> m.multiMatch(mm -> mm.query(condition.keyword()).fields(KEYWORD_FIELDS)
                .type(TextQueryType.BestFields).tieBreaker(0.3)));
        Query base = bool.build()._toQuery();
        return Query.of(q -> q.scriptScore(ss -> ss.query(base)
                .script(sc -> sc.source(src -> src.scriptString(FOLLOWER_BOOST_SCRIPT))
                        .params("a", JsonData.of(ranking.followerBoost()))
                        .params("v", JsonData.of(ranking.verifiedBoost())))));
    }

    List<SortOptions> sort(InfluencerSearchCondition condition, double ratingBaseline) {
        List<SortOptions> sort = new ArrayList<>();
        InfluencerSort option = condition.sort();
        if (option == InfluencerSort.RATING) {
            sort.add(SortOptions.of(o -> o.script(ss -> ss.type(ScriptSortType.Number).order(SortOrder.Desc)
                    .script(sc -> sc.source(src -> src.scriptString(BAYESIAN_RATING_SCRIPT))
                            .params("m", JsonData.of(ranking.ratingPriorReviews()))
                            .params("c", JsonData.of(ratingBaseline))))));
            sort.add(followers());
        } else if (option == InfluencerSort.FOLLOWERS) {
            sort.add(followers());
            sort.add(score());
        } else if (condition.keyword() == null && condition.contentCategories().isEmpty()) {
            sort.add(followers());
        } else {
            sort.add(score());
            sort.add(followers());
        }
        return sort;
    }

    private static SortOptions followers() {
        return SortOptions.of(o -> o.field(f -> f.field("followerCount").order(SortOrder.Desc)));
    }

    private static SortOptions score() {
        return SortOptions.of(o -> o.score(s -> s.order(SortOrder.Desc)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static InfluencerSearchItem item(Hit<Map> hit) {
        Map<String, Object> source = hit.source();
        List<Map<String, Object>> categories = (List<Map<String, Object>>) source.getOrDefault("contentCategories", List.of());
        Object average = source.get("ratingAverage");
        return new InfluencerSearchItem(Long.parseLong(hit.id()), (String) source.get("nickname"),
                (String) source.get("role"),
                categories.stream().map(c -> new InfluencerSearchItem.Category((String) c.get("code"), (String) c.get("name")))
                        .toList(),
                (String) source.get("bio"), (String) source.get("representativeChannelName"),
                number(source.get("followerCount")),
                new InfluencerSearchItem.Rating(average == null ? null : ((Number) average).doubleValue(),
                        number(source.get("ratingCount"))));
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    /**
     * @param total 조건에 맞는 전체 수(최대값까지만 센다)
     * @param items 이 페이지
     */
    public record SearchResult(long total, List<InfluencerSearchItem> items) {
    }
}
