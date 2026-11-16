package com.hapbang.auxiliary.search.lecture.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.app.exception.SearchErrorCode;
import com.hapbang.auxiliary.search.app.exception.SearchException;
import com.hapbang.auxiliary.search.common.app.SearchMetrics;
import com.hapbang.auxiliary.search.lecture.app.dto.LectureSuggestion;
import com.hapbang.auxiliary.search.lecture.out.LectureSearcher;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 강의 검색어 자동완성(#973): 한도를 먼저 세고, 빈 검색어는 ES를 부르지 않고, ES 장애는 503 */
class LectureSuggestionFacadeTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LectureSearchRateLimit rateLimit = mock(LectureSearchRateLimit.class);
    private final LectureSearcher searcher = mock(LectureSearcher.class);
    private final LectureSuggestionFacade facade = new LectureSuggestionFacade(rateLimit, searcher,
            new SearchMetrics(registry));

    @Test
    @DisplayName("검색어를 정리해 후보 5개를 찾고, 자동완성 대상(lecture_suggestion)으로 요청 수를 센다")
    void suggests() {
        List<LectureSuggestion> found = List.of(new LectureSuggestion(1L, "프리미어 기초", "편집왕"));
        when(searcher.suggest("프리미어 기", LectureSuggestionFacade.SIZE)).thenReturn(found);

        assertThat(facade.suggest(Requester.member(1L), "  프리미어   기 ")).isEqualTo(found);

        verify(rateLimit).acquire(Requester.member(1L));
        assertThat(registry.counter("hapbang.search.requests", "target", "lecture_suggestion", "outcome", "ok").count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("공백뿐인 검색어는 ES를 부르지 않고 빈 목록이다. 한도는 센다")
    void blankKeyword() {
        assertThat(facade.suggest(Requester.guest("ab12"), "   ")).isEmpty();

        verify(rateLimit).acquire(Requester.guest("ab12"));
        verify(searcher, never()).suggest(anyString(), anyInt());
    }

    @Test
    @DisplayName("ES에 닿지 못하면 503 SEARCH_UNAVAILABLE이고 error 지표를 남긴다")
    void unavailable() {
        when(searcher.suggest(anyString(), anyInt())).thenThrow(new IllegalStateException("down"));

        assertThatThrownBy(() -> facade.suggest(Requester.member(1L), "프리"))
                .isInstanceOf(SearchException.class)
                .extracting(e -> ((SearchException) e).getErrorCode()).isEqualTo(SearchErrorCode.SEARCH_UNAVAILABLE);
        assertThat(registry.counter("hapbang.search.requests", "target", "lecture_suggestion", "outcome", "error")
                .count()).isEqualTo(1);
    }
}
