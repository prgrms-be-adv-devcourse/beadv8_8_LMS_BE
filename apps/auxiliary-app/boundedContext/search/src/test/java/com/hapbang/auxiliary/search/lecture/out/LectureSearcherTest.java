package com.hapbang.auxiliary.search.lecture.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

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
                0, 3);
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
        return searcher.search(new LectureSearchCondition(keyword, sort, 0, 20), 0, 1_000).items();
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
        store.writeLecture(new LectureSyncDto(id, sellerUserId, title, description, onSale, false, contents, 1L), seller,
                rating == null ? LectureRating.NONE : rating, NOW);
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
