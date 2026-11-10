package com.hapbang.auxiliary.search.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import com.hapbang.auxiliary.search.app.exception.SearchErrorCode;
import com.hapbang.auxiliary.search.app.exception.SearchException;
import com.hapbang.auxiliary.search.out.InfluencerSearcher;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 검색 Facade: 검색어·분야 다듬기, ES 장애(#771). 정렬·페이지 제한은 API 테스트가 본다 */
class InfluencerSearchFacadeTest {

    private final InfluencerSearcher searcher = mock(InfluencerSearcher.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final ZeroResultKeywords zeroResultKeywords = new ZeroResultKeywords();

    private final InfluencerSearchFacade facade = new InfluencerSearchFacade(searcher, mock(RatingBaseline.class),
            new SearchMetrics(registry), zeroResultKeywords);

    @Test
    @DisplayName("검색어는 앞뒤·연속 공백을 정리하고, 비면 검색어 없음이다. 분야는 빈 값·중복을 뺀다")
    void normalizes() {
        assertThat(InfluencerSearchFacade.keyword("  메이플   스토리 ")).isEqualTo("메이플 스토리");
        assertThat(InfluencerSearchFacade.keyword("   ")).isNull();
        assertThat(InfluencerSearchFacade.keyword(null)).isNull();
        assertThat(InfluencerSearchFacade.codes(Arrays.asList("GAME", " ", null, "GAME", " MAPLESTORY ")))
                .containsExactly("GAME", "MAPLESTORY");
    }

    @Test
    @DisplayName("필터 때문에 0건이면 0건 검색어로 세지 않는다(사전·동의어 문제가 아님). 필터 없이 0건이면 센다")
    void zeroResultOnlyWithoutFilter() {
        when(searcher.search(any(), anyDouble(), anyInt())).thenReturn(new InfluencerSearcher.SearchResult(0, List.of()));
        PageRequest page = PageRequest.of(0, 20);

        for (long user = 1; user <= 2; user++) {
            facade.search(user, "메이플", List.of(), null, 10_000_000L, null, page);
            facade.search(user, "메이플", List.of("GAME"), null, null, null, page);
            facade.search(user, "메이플", List.of(), true, null, null, page);
            facade.search(user, "메이플", List.of(), null, null, 4.9, page);
        }
        assertThat(zeroResultKeywords.flush()).isEmpty();
        assertThat(registry.counter("hapbang.search.zero.results", "target", "influencer").count()).isZero();

        facade.search(1L, "롤토체스", List.of(), null, null, null, page);
        facade.search(2L, "롤토체스", List.of(), null, null, null, page);
        assertThat(zeroResultKeywords.flush()).containsOnlyKeys("롤토체스");
        assertThat(registry.counter("hapbang.search.zero.results", "target", "influencer").count()).isEqualTo(2);
    }

    @Test
    @DisplayName("ES에 닿지 못하면 503 SEARCH_UNAVAILABLE이고 error 지표를 남긴다")
    void unavailable() {
        when(searcher.search(any(), anyDouble(), anyInt())).thenThrow(new IllegalStateException("ES 장애"));

        assertThatThrownBy(() -> facade.search(1L, "메이플", List.of(), null, null, null, PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(SearchException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SearchErrorCode.SEARCH_UNAVAILABLE));
        assertThat(registry.counter("hapbang.search.requests", "target", "influencer", "outcome", "error").count())
                .isEqualTo(1);
    }
}
