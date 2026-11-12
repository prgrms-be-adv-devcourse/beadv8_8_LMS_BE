package com.hapbang.auxiliary.search.lecture.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.hapbang.auxiliary.search.app.exception.SearchErrorCode;
import com.hapbang.auxiliary.search.app.exception.SearchException;
import com.hapbang.auxiliary.search.common.app.SearchMetrics;
import com.hapbang.auxiliary.search.lecture.out.LectureSearcher;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 강의 검색 Facade(#883): 정렬 허용 목록, 1,000건 제한, ES 장애 503, 평균 C를 쓰는 경우, 0건 검색어(회원·비회원) */
class LectureSearchFacadeTest {

    private final LectureSearcher searcher = mock(LectureSearcher.class);
    private final LectureRatingBaseline baseline = mock(LectureRatingBaseline.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LectureZeroResultKeywords zeroResultKeywords = new LectureZeroResultKeywords();
    private final LectureSearchFacade facade = new LectureSearchFacade(searcher, baseline, new SearchMetrics(registry),
            zeroResultKeywords);

    @Test
    @DisplayName("정렬은 없음·latest,desc·rating,desc만 받고, 그 밖은 강의 정렬을 알리는 400 UNSUPPORTED_SORT다")
    void sorts() {
        assertThat(LectureSearchFacade.sort(Sort.unsorted())).isEqualTo(LectureSort.DEFAULT);
        assertThat(LectureSearchFacade.sort(Sort.by(Sort.Order.desc("latest")))).isEqualTo(LectureSort.LATEST);
        assertThat(LectureSearchFacade.sort(Sort.by(Sort.Order.desc("rating")))).isEqualTo(LectureSort.RATING);

        for (Sort sort : List.of(Sort.by(Sort.Order.asc("latest")), Sort.by(Sort.Order.desc("createdAt")),
                Sort.by(Sort.Order.desc("followers")), Sort.by(Sort.Order.desc("latest"), Sort.Order.desc("rating")))) {
            assertThatThrownBy(() -> LectureSearchFacade.sort(sort)).isInstanceOfSatisfying(SearchException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(SearchErrorCode.UNSUPPORTED_SORT);
                assertThat(e.getMessage()).contains("latest,desc").contains("rating,desc");
            });
        }
    }

    @Test
    @DisplayName("앞에서 1,000건을 넘는 페이지는 ES를 부르지 않고 400 PAGE_TOO_DEEP이다")
    void pageTooDeep() {
        assertThatThrownBy(() -> facade.search(Requester.member(1L), "프리미어", PageRequest.of(50, 20)))
                .isInstanceOfSatisfying(SearchException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SearchErrorCode.PAGE_TOO_DEEP));
        verify(searcher, never()).search(any(), anyDouble(), anyInt());

        when(searcher.search(any(), anyDouble(), anyInt())).thenReturn(new LectureSearcher.SearchResult(1, List.of()));
        facade.search(Requester.member(1L), "프리미어", PageRequest.of(49, 20));
    }

    @Test
    @DisplayName("ES에 닿지 못하면(평균 C를 구하지 못한 경우 포함) 503 SEARCH_UNAVAILABLE이고 error 지표를 남긴다")
    void unavailable() {
        when(searcher.search(any(), anyDouble(), anyInt())).thenThrow(new IllegalStateException("ES 장애"));
        assertThatThrownBy(() -> facade.search(Requester.member(1L), null, PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(SearchException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SearchErrorCode.SEARCH_UNAVAILABLE));

        when(baseline.get()).thenThrow(new IllegalStateException("평균 C 실패"));
        assertThatThrownBy(() -> facade.search(Requester.member(1L), "프리미어", PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(SearchException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SearchErrorCode.SEARCH_UNAVAILABLE));
        assertThat(registry.counter("hapbang.search.requests", "target", "lecture", "outcome", "error").count())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("평균 C는 검색어가 있는 기본 순서와 평점순에만 구하고, 최신순 목록에는 구하지 않는다")
    void baselineOnlyWhenNeeded() {
        when(searcher.search(any(), anyDouble(), anyInt())).thenReturn(new LectureSearcher.SearchResult(1, List.of()));
        when(baseline.get()).thenReturn(4.2);

        facade.search(Requester.member(1L), null, PageRequest.of(0, 20));
        facade.search(Requester.member(1L), "프리미어", PageRequest.of(0, 20, Sort.by(Sort.Order.desc("latest"))));
        verify(baseline, never()).get();
        verify(searcher, org.mockito.Mockito.times(2)).search(any(), eq(0.0), anyInt());

        facade.search(Requester.member(1L), "프리미어", PageRequest.of(0, 20));
        facade.search(Requester.member(1L), null, PageRequest.of(0, 20, Sort.by(Sort.Order.desc("rating"))));
        verify(searcher, org.mockito.Mockito.times(2)).search(any(), eq(4.2), anyInt());
    }

    @Test
    @DisplayName("검색어로 0건이면 회원은 회원 ID, 비회원은 IP 해시로 서로 다른 검색자를 센다. IP를 믿을 수 없는 비회원은 세지 않는다")
    void zeroResultKeywords() {
        when(searcher.search(any(), anyDouble(), anyInt())).thenReturn(new LectureSearcher.SearchResult(0, List.of()));
        PageRequest page = PageRequest.of(0, 20);

        facade.search(Requester.guest(null), "다빈치", page);
        facade.search(Requester.guest(null), "다빈치", page);
        facade.search(Requester.member(1L), null, page);
        assertThat(zeroResultKeywords.flush()).isEmpty();

        facade.search(Requester.member(1L), "다빈치", page);
        facade.search(Requester.guest("ab12cd34ef56ab78"), "  다빈치 ", page);
        assertThat(zeroResultKeywords.flush()).containsOnlyKeys("다빈치");
        assertThat(registry.counter("hapbang.search.zero.results", "target", "lecture").count()).isEqualTo(4);
    }

    @Test
    @DisplayName("검색어는 앞뒤·연속 공백을 정리하고, 비면 검색어 없음이다")
    void normalizes() {
        assertThat(LectureSearchFacade.keyword("  프리미어   컷편집 ")).isEqualTo("프리미어 컷편집");
        assertThat(LectureSearchFacade.keyword("   ")).isNull();
        assertThat(LectureSearchFacade.keyword(null)).isNull();
    }
}
