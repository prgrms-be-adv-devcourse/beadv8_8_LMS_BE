package com.hapbang.auxiliary.search.lecture.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.lecture.app.LectureSearchCondition;
import com.hapbang.auxiliary.search.lecture.app.LectureSort;
import com.hapbang.auxiliary.search.lecture.app.dto.LectureSearchItem;
import com.hapbang.auxiliary.search.lecture.config.LectureRankingProperties;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;
import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;

/**
 * 강의 검색 쿼리(#883): 보일 강의 조건, 검색 필드, 정렬, highlights(HTML escape), 평점 반올림, 평균 C. nori ES 컨테이너로 돈다.
 * 순위의 세부(가중치·계수)는 강의 golden query 테스트가 본다.
 */
class LectureSearcherTest {

    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");

    private final ElasticsearchClient client = SearchElasticsearch.client();
    private final LectureDocumentStore store = new LectureDocumentStore(client);
    private final LectureSearcher searcher = new LectureSearcher(client, new LectureRankingProperties(null, null, null));

    @BeforeEach
    void reset() throws Exception {
        SearchElasticsearch.reset(client);
        SearchElasticsearch.createLectureIndex(client);
    }

    @Test
    @DisplayName("판매 중이고 판매자가 정상인 강의만 보인다. 판매 중지 강의와 정지된 판매자의 강의는 검색어가 맞아도 빠진다")
    void visibleOnly() throws Exception {
        write(1L, "프리미어 입문", "소개", List.of(), seller(7L, "편집장", true), null);
        write(2L, "프리미어 중급", "소개", List.of(), seller(7L, "편집장", true), null, false);
        write(3L, "프리미어 고급", "소개", List.of(), seller(8L, "정지된판매자", false), null);
        refresh();

        assertThat(ids(search("프리미어", LectureSort.DEFAULT))).containsExactly(1L);
        assertThat(ids(search(null, LectureSort.DEFAULT))).containsExactly(1L);
    }

    @Test
    @DisplayName("제목·콘텐츠 제목·판매자 닉네임·소개 모두에서 찾는다")
    void keywordFields() throws Exception {
        write(1L, "프리미어 입문", "소개", List.of(), seller(7L, "편집장", true), null);
        write(2L, "영상 기초", "소개", List.of("3강 프리미어 설치"), seller(7L, "편집장", true), null);
        write(3L, "영상 기초", "소개", List.of(), seller(8L, "프리미어장인", true), null);
        write(4L, "영상 기초", "처음 쓰는 분도 프리미어로 브이로그를 만든다", List.of(), seller(9L, "다른사람", true), null);
        write(5L, "포토샵 입문", "소개", List.of(), seller(9L, "다른사람", true), null);
        refresh();

        assertThat(ids(search("프리미어", LectureSort.DEFAULT))).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
    }

    @Test
    @DisplayName("기본은 검색어가 있으면 관련도순, 없으면 최신순(강의 ID 내림차순)이고, latest는 검색어가 있어도 최신순이다")
    void defaultAndLatest() throws Exception {
        write(1L, "프리미어 프로 컷편집 프리미어", "소개", List.of(), seller(7L, "편집장", true), null);
        write(2L, "영상 기초", "프리미어 조금", List.of(), seller(7L, "편집장", true), null);
        write(3L, "포토샵", "소개", List.of(), seller(7L, "편집장", true), null);
        refresh();

        assertThat(ids(search("프리미어", LectureSort.DEFAULT))).containsExactly(1L, 2L);
        assertThat(ids(search(null, LectureSort.DEFAULT))).containsExactly(3L, 2L, 1L);
        assertThat(ids(search("프리미어", LectureSort.LATEST))).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("평점순은 베이지안 평균이라 리뷰 1건 5.0이 리뷰가 많은 4.7보다 아래이고, 리뷰 없는 강의는 전체 평균으로 본다")
    void ratingSort() throws Exception {
        Seller seller = seller(7L, "편집장", true);
        write(1L, "강의 하나", "소개", List.of(), seller, new LectureRating(5.0, 1));
        write(2L, "강의 둘", "소개", List.of(), seller, new LectureRating(4.7, 30));
        write(3L, "강의 셋", "소개", List.of(), seller, null);
        write(4L, "강의 넷", "소개", List.of(), seller, new LectureRating(3.0, 20));
        refresh();

        double baseline = searcher.ratingBaseline();
        assertThat(baseline).isEqualTo((5.0 + 4.7 * 30 + 3.0 * 20) / 51, org.assertj.core.data.Offset.offset(1e-9));
        List<LectureSearchItem> items = searcher.search(new LectureSearchCondition(null, LectureSort.RATING, 0, 20),
                baseline, 1_000).items();
        assertThat(ids(items)).containsExactly(2L, 1L, 3L, 4L);
    }

    @Test
    @DisplayName("가격순은 오름·내림이고 같은 가격은 강의 ID 내림차순, 가격 없는 강의는 어느 쪽이든 맨 뒤다. 응답에 가격을 싣는다(#909)")
    void priceSort() throws Exception {
        writePriced(1L, 30_000L);
        writePriced(2L, 10_000L);
        writePriced(3L, null);
        writePriced(4L, 10_000L);
        writePriced(5L, 50_000L);
        refresh();

        List<LectureSearchItem> ascending = search(null, LectureSort.PRICE_ASC);
        assertThat(ids(ascending)).containsExactly(4L, 2L, 1L, 5L, 3L);
        assertThat(ascending).extracting(LectureSearchItem::priceHp)
                .containsExactly(10_000L, 10_000L, 30_000L, 50_000L, null);
        assertThat(ids(search("강의", LectureSort.PRICE_DESC))).containsExactly(5L, 1L, 4L, 2L, 3L);
    }

    @Test
    @DisplayName("alias가 가격 mapping이 없는 옛 index를 가리켜도(재색인이 옮기기 전) 가격순은 거절되지 않고 최신순으로 나온다(#909)")
    void priceSortOnIndexWithoutPriceMapping() throws Exception {
        SearchElasticsearch.reset(client);
        String old = LectureIndexDefinition.INDEX_PREFIX + "old000000000";
        client.indices().create(c -> c.index(old).mappings(m -> m.dynamic(DynamicMapping.Strict)
                .properties("lectureId", p -> p.long_(l -> l))
                .properties("hidden", p -> p.boolean_(b -> b))
                .properties("sellerActive", p -> p.boolean_(b -> b))
                .properties("title", p -> p.text(t -> t))));
        client.indices().putAlias(a -> a.index(old).name(LectureIndexDefinition.ALIAS));
        for (long id = 1; id <= 2; id++) {
            long lectureId = id;
            client.index(i -> i.index(old).id(String.valueOf(lectureId)).document(Map.of("lectureId", lectureId,
                    "hidden", false, "sellerActive", true, "title", "강의 " + lectureId)));
        }
        refresh();

        List<LectureSearchItem> items = search(null, LectureSort.PRICE_ASC);

        assertThat(ids(items)).containsExactly(2L, 1L);
        assertThat(items).extracting(LectureSearchItem::priceHp).containsOnlyNulls();
    }

    @Test
    @DisplayName("highlights는 원문의 HTML을 escape하고 걸린 말만 <em>으로 감싼다. 콘텐츠 제목은 걸린 것만, 소개는 주변 조각이다")
    void highlightsEscapeHtml() throws Exception {
        String filler = "영상 편집을 처음 배우는 분을 위한 강의 소개 문장입니다. ";
        String longDescription = filler.repeat(10) + "이 강의는 <b>프리미어</b>로 브이로그를 만듭니다. " + filler.repeat(10);
        write(1L, "<script>alert(1)</script> 프리미어 입문", longDescription,
                List.of("1강 설치", "2강 \"프리미어\" 컷 & 자막"), seller(7L, "편집장", true), null);
        refresh();

        LectureSearchItem item = search("프리미어", LectureSort.DEFAULT).getFirst();

        assertThat(item.title()).isEqualTo("<script>alert(1)</script> 프리미어 입문");
        LectureSearchItem.Highlights highlights = item.highlights();
        assertThat(highlights.title()).isEqualTo("&lt;script&gt;alert(1)&lt;&#x2F;script&gt; <em>프리미어</em> 입문");
        assertThat(highlights.description()).contains("&lt;b&gt;<em>프리미어</em>&lt;&#x2F;b&gt;")
                .doesNotContain("<b>").hasSizeLessThan(longDescription.length() / 3);
        assertThat(highlights.contentTitles()).containsExactly("2강 &quot;<em>프리미어</em>&quot; 컷 &amp; 자막");
        assertThat(highlights.title() + highlights.description() + highlights.contentTitles())
                .doesNotContain("<script").doesNotContain("<b>");
    }

    @Test
    @DisplayName("검색어가 없으면 highlights가 없다. 평균은 소수 첫째 자리 반올림이고 리뷰가 없으면 null이다")
    void noKeywordNoHighlightsAndRounding() throws Exception {
        write(1L, "강의", "소개", List.of(), seller(7L, "편집장", true), new LectureRating(4.25, 4));
        write(2L, "강의", "소개", List.of(), null, null);
        refresh();

        List<LectureSearchItem> items = search(null, LectureSort.DEFAULT);

        assertThat(items).extracting(LectureSearchItem::highlights).containsOnlyNulls();
        assertThat(items.get(0)).extracting(LectureSearchItem::lectureId, LectureSearchItem::sellerNickname,
                LectureSearchItem::averageRating, LectureSearchItem::reviewCount).containsExactly(2L, null, null, 0L);
        assertThat(items.get(1)).extracting(LectureSearchItem::lectureId, LectureSearchItem::sellerUserId,
                LectureSearchItem::sellerNickname, LectureSearchItem::averageRating, LectureSearchItem::reviewCount)
                .containsExactly(1L, 7L, "편집장", 4.3, 4L);
    }

    @Test
    @DisplayName("검색어가 있으면 highlights 객체가 늘 있다. 닉네임으로만 걸리면 닉네임 칸만 채우고 나머지는 비어 있다")
    void highlightsAlwaysWithKeyword() throws Exception {
        write(1L, "영상 기초", "소개", List.of("1강 설치"), seller(8L, "프리미어장인", true), null);
        refresh();

        LectureSearchItem.Highlights highlights = search("프리미어", LectureSort.DEFAULT).getFirst().highlights();

        assertThat(highlights).isNotNull();
        assertThat(highlights.sellerNickname()).isEqualTo("<em>프리미어</em>장인");
        assertThat(highlights.title()).isNull();
        assertThat(highlights.description()).isNull();
        assertThat(highlights.contentTitles()).isEmpty();
    }

    @Test
    @DisplayName("평균 C를 모르면(null) 평점 가산 없이 글 점수만 쓴다. 글이 같으면 강의 ID 내림차순이다")
    void noBaselineNoBoost() throws Exception {
        write(1L, "라이브 방송 기획", "소개", List.of(), seller(7L, "편집장", true), new LectureRating(5.0, 50));
        write(2L, "라이브 방송 기획", "소개", List.of(), seller(7L, "편집장", true), new LectureRating(2.0, 50));
        refresh();
        LectureSearchCondition condition = new LectureSearchCondition("라이브 방송 기획", LectureSort.DEFAULT, 0, 20);

        assertThat(ids(searcher.search(condition, null, 1_000).items())).containsExactly(2L, 1L);
        assertThat(ids(searcher.search(condition, 3.5, 1_000).items())).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("동의어로도 찾는다(공통 동의어 묶음)")
    void synonyms() throws Exception {
        write(1L, "배틀그라운드 에임 강의", "소개", List.of(), seller(7L, "편집장", true), null);
        refresh();

        assertThat(ids(search("배그", LectureSort.DEFAULT))).containsExactly(1L);
    }

    @Test
    @DisplayName("전체 수는 최대값까지만 센다")
    void totalCapped() throws Exception {
        for (long id = 1; id <= 5; id++) {
            write(id, "강의", "소개", List.of(), seller(7L, "편집장", true), null);
        }
        refresh();

        LectureSearcher.SearchResult result = searcher.search(new LectureSearchCondition(null, LectureSort.DEFAULT, 0, 2),
                null, 3);
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.items()).hasSize(2);
    }

    @Test
    @DisplayName("alias가 없으면(처음 적재 전) 검색도 평균 C도 IllegalStateException이다")
    void noAlias() throws Exception {
        SearchElasticsearch.reset(client);

        assertThatThrownBy(() -> search("프리미어", LectureSort.DEFAULT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(searcher::ratingBaseline).isInstanceOf(IllegalStateException.class);
    }

    private List<LectureSearchItem> search(String keyword, LectureSort sort) {
        return searcher.search(new LectureSearchCondition(keyword, sort, 0, 20), null, 1_000).items();
    }

    private void write(long id, String title, String description, List<String> contentTitles, Seller seller,
            LectureRating rating) {
        write(id, title, description, contentTitles, seller, rating, true);
    }

    private void write(long id, String title, String description, List<String> contentTitles, Seller seller,
            LectureRating rating, boolean onSale) {
        List<LectureContentSyncDto> contents = new java.util.ArrayList<>();
        for (int i = 0; i < contentTitles.size(); i++) {
            contents.add(new LectureContentSyncDto((long) i + 1, contentTitles.get(i), i + 1));
        }
        long sellerUserId = seller == null ? 99L : seller.userId();
        store.writeLecture(new LectureSyncDto(id, sellerUserId, title, description, 10_000L, onSale, false, contents, 1L), seller,
                rating == null ? LectureRating.NONE : rating, NOW);
    }

    private void writePriced(long id, Long priceHp) {
        store.writeLecture(new LectureSyncDto(id, 7L, "강의 " + id, "소개", priceHp, true, false, List.of(), 1L),
                seller(7L, "편집장", true), LectureRating.NONE, NOW);
    }

    private static Seller seller(long userId, String nickname, boolean active) {
        return new Seller(userId, nickname, active);
    }

    private void refresh() throws Exception {
        client.indices().refresh(r -> r.index(LectureIndexDefinition.ALIAS));
    }

    private static List<Long> ids(List<LectureSearchItem> items) {
        return items.stream().map(LectureSearchItem::lectureId).toList();
    }
}
