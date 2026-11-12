package com.hapbang.auxiliary.search.lecture.out;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.config.SearchElasticsearchConfig;
import com.hapbang.auxiliary.search.lecture.app.LectureSearchCondition;
import com.hapbang.auxiliary.search.lecture.app.LectureSort;
import com.hapbang.auxiliary.search.lecture.app.dto.LectureSearchItem;
import com.hapbang.auxiliary.search.lecture.config.LectureRankingProperties;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ScriptSortType;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.HighlighterEncoder;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.util.NamedValue;

/**
 * 강의 검색 쿼리(#883). 인플루언서 검색({@code InfluencerSearcher})과 같은 모양이다.
 * <ul>
 *     <li>filter: 검색에 보일 강의 = {@code hidden=false} 그리고 {@code sellerActive=true}(#882 Q2)</li>
 *     <li>must(검색어): {@code multi_match} best_fields, 제목 ×3, 콘텐츠 제목 ×2, 판매자 닉네임 ×2, 소개 ×1, tie_breaker 0.3(L11).
 *     검색 분석기가 동의어를 펼친다</li>
 *     <li>검색어가 있으면 {@code script_score}로 점수 × (1 + b × 베이지안 평균 / 5). 글이 먼저고 평점은 비슷할 때만 가른다(L11).
 *     전체 평균 C를 모르면(null) 가산 없이 글 점수만 쓴다(#883)</li>
 *     <li>highlight(검색어가 있을 때): {@code encoder: html}이라 원문의 HTML 특수 문자를 escape하고 걸린 말만 {@code <em>}으로 감싼다(L12).
 *     제목·판매자 닉네임은 통째로, 소개는 걸린 곳 주변 한 조각(약 {@value #DESCRIPTION_FRAGMENT_SIZE}자), 콘텐츠 제목은 걸린 것만 통째로.
 *     검색어가 있으면 걸린 칸이 없어도 {@code highlights} 객체를 준다(검색어 없음과 구분)</li>
 * </ul>
 * 순서: 기본은 검색어가 있으면 점수, 없으면 최신. {@code latest}는 강의 ID 내림차순. {@code rating}은 베이지안 평균 (n×R + m×C)/(n+m) →
 * 리뷰 수. {@code price}는 가격 오름·내림이고 가격 없는 강의(옛 메시지로 만든 문서)는 어느 쪽이든 맨 뒤다(#909, 무료 강의가 없어 null은 0원이 아니라
 * 모르는 값이다). 모든 순서의 마지막은 강의 ID 내림차순이다(동점이 많아 없으면 문서를 다시 쓸 때 페이지 사이에서 같은 강의가 또 나오거나 빠진다).
 */
@Component
public class LectureSearcher {

    /** 검색어를 찾는 필드와 가중치(L11 초기값, golden 테스트가 고정한다) */
    static final List<String> KEYWORD_FIELDS = List.of("title^3", "contentTitles^2", "sellerNickname^2", "description^1");

    static final int DESCRIPTION_FRAGMENT_SIZE = 100;

    /** 베이지안 평균. 리뷰가 없으면 전체 평균 c다 */
    static final String BAYESIAN_RATING = """
            double n = doc['reviewCount'].size() == 0 ? 0 : doc['reviewCount'].value;
            double r = doc['averageRating'].size() == 0 ? 0 : doc['averageRating'].value;
            double bayes = (n * r + params.m * params.c) / (n + params.m);
            """;

    static final String RATING_BOOST_SCRIPT = BAYESIAN_RATING + "return _score * (1 + params.b * bayes / 5);";

    static final String RATING_SORT_SCRIPT = BAYESIAN_RATING + "return bayes;";

    private final ElasticsearchClient client;
    private final LectureRankingProperties ranking;

    public LectureSearcher(@Qualifier(SearchElasticsearchConfig.CLIENT) ElasticsearchClient client,
            LectureRankingProperties ranking) {
        this.client = client;
        this.ranking = ranking;
    }

    /**
     * @param ratingBaseline 베이지안 평균의 전체 평균 C. 모르면 null(평점순에는 꼭 있어야 한다)
     * @param maxTotal       전체 건수를 이 값까지만 센다(페이지를 넘길 수 있는 범위)
     * @throws IllegalStateException ES에 닿지 못했거나 거절당했을 때(alias가 없는 처음 적재 전 포함)
     */
    @SuppressWarnings("rawtypes")
    public SearchResult search(LectureSearchCondition condition, Double ratingBaseline, int maxTotal) {
        try {
            SearchResponse<Map> response = client.search(s -> {
                s.index(LectureIndexDefinition.ALIAS)
                        .query(query(condition, ratingBaseline))
                        .sort(sort(condition, ratingBaseline))
                        .from((int) condition.offset())
                        .size(condition.size())
                        .trackTotalHits(t -> t.count(maxTotal));
                if (condition.keyword() != null) {
                    s.highlight(highlight());
                }
                return s;
            }, Map.class);
            boolean keyword = condition.keyword() != null;
            List<LectureSearchItem> items = response.hits().hits().stream().map(hit -> item(hit, keyword)).toList();
            long total = response.hits().total() == null ? items.size() : response.hits().total().value();
            return new SearchResult(Math.min(total, maxTotal), items);
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("강의를 검색하지 못했습니다", e);
        }
    }

    /** 검색에 보이는 강의의 리뷰 전체 평균(리뷰 수 가중). 리뷰가 하나도 없으면 null */
    public Double ratingBaseline() {
        try {
            return client.search(s -> s.index(LectureIndexDefinition.ALIAS).size(0)
                    .query(q -> q.bool(visible().build()))
                    .aggregations("c", a -> a.weightedAvg(w -> w.value(v -> v.field("averageRating"))
                            .weight(v -> v.field("reviewCount")))), Void.class)
                    .aggregations().get("c").weightedAvg().value();
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("강의 평점 전체 평균을 구하지 못했습니다", e);
        }
    }

    Query query(LectureSearchCondition condition, Double ratingBaseline) {
        BoolQuery.Builder bool = visible();
        if (condition.keyword() == null) {
            return bool.build()._toQuery();
        }
        bool.must(m -> m.multiMatch(mm -> mm.query(condition.keyword()).fields(KEYWORD_FIELDS)
                .type(TextQueryType.BestFields).tieBreaker(0.3)));
        Query base = bool.build()._toQuery();
        if (ratingBaseline == null) {
            return base;
        }
        return Query.of(q -> q.scriptScore(ss -> ss.query(base)
                .script(sc -> sc.source(src -> src.scriptString(RATING_BOOST_SCRIPT))
                        .params("b", JsonData.of(ranking.ratingBoost()))
                        .params("m", JsonData.of(ranking.ratingPriorReviews()))
                        .params("c", JsonData.of(ratingBaseline)))));
    }

    /** 검색에 보일 강의: 판매 중(숨기지 않음)이고 판매자가 정상 */
    private static BoolQuery.Builder visible() {
        return new BoolQuery.Builder()
                .filter(f -> f.term(t -> t.field("hidden").value(false)))
                .filter(f -> f.term(t -> t.field("sellerActive").value(true)));
    }

    List<SortOptions> sort(LectureSearchCondition condition, Double ratingBaseline) {
        List<SortOptions> sort = new ArrayList<>();
        if (condition.sort() == LectureSort.RATING) {
            sort.add(SortOptions.of(o -> o.script(ss -> ss.type(ScriptSortType.Number).order(SortOrder.Desc)
                    .script(sc -> sc.source(src -> src.scriptString(RATING_SORT_SCRIPT))
                            .params("m", JsonData.of(ranking.ratingPriorReviews()))
                            .params("c", JsonData.of(ratingBaseline))))));
            sort.add(SortOptions.of(o -> o.field(f -> f.field("reviewCount").order(SortOrder.Desc))));
        } else if (condition.sort() == LectureSort.PRICE_ASC || condition.sort() == LectureSort.PRICE_DESC) {
            SortOrder order = condition.sort() == LectureSort.PRICE_ASC ? SortOrder.Asc : SortOrder.Desc;
            sort.add(SortOptions.of(o -> o.field(f -> f.field("priceHp").order(order).missing("_last"))));
        } else if (condition.sort() == LectureSort.DEFAULT && condition.keyword() != null) {
            sort.add(SortOptions.of(o -> o.score(s -> s.order(SortOrder.Desc))));
        }
        sort.add(SortOptions.of(o -> o.field(f -> f.field("lectureId").order(SortOrder.Desc))));
        return sort;
    }

    static Highlight highlight() {
        return Highlight.of(h -> h.encoder(HighlighterEncoder.Html).preTags("<em>").postTags("</em>")
                .fields(NamedValue.of("title", HighlightField.of(f -> f.numberOfFragments(0))),
                        NamedValue.of("sellerNickname", HighlightField.of(f -> f.numberOfFragments(0))),
                        NamedValue.of("description", HighlightField.of(f -> f.fragmentSize(DESCRIPTION_FRAGMENT_SIZE)
                                .numberOfFragments(1))),
                        NamedValue.of("contentTitles", HighlightField.of(f -> f.numberOfFragments(0)))));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static LectureSearchItem item(Hit<Map> hit, boolean keyword) {
        Map<String, Object> source = hit.source();
        Object average = source.get("averageRating");
        Object price = source.get("priceHp");
        return new LectureSearchItem(number(source.get("lectureId")), (String) source.get("title"),
                price == null ? null : ((Number) price).longValue(), number(source.get("sellerUserId")), (String) source.get("sellerNickname"),
                average == null ? null : round(((Number) average).doubleValue()), number(source.get("reviewCount")),
                keyword ? highlights(hit.highlight()) : null);
    }

    /** 검색어가 있을 때만 부른다. 걸린 칸이 없으면 칸이 비어 있는 객체다 */
    private static LectureSearchItem.Highlights highlights(Map<String, List<String>> highlight) {
        Map<String, List<String>> fields = highlight == null ? Map.of() : highlight;
        return new LectureSearchItem.Highlights(first(fields.get("title")), first(fields.get("sellerNickname")),
                first(fields.get("description")), fields.get("contentTitles"));
    }

    private static String first(List<String> fragments) {
        return fragments == null || fragments.isEmpty() ? null : fragments.getFirst();
    }

    /** 강의 목록과 같이 소수 첫째 자리 반올림(강의 앱 {@code LectureRating}) */
    static double round(double average) {
        return BigDecimal.valueOf(average).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    /**
     * @param total 조건에 맞는 전체 수(최대값까지만 센다)
     * @param items 이 페이지
     */
    public record SearchResult(long total, List<LectureSearchItem> items) {
    }
}
