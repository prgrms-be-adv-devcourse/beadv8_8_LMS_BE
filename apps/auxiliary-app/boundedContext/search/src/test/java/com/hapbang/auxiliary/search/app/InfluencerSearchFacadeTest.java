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

    private final InfluencerSearchFacade facade = new InfluencerSearchFacade(searcher, mock(RatingBaseline.class),
            new SearchMetrics(registry), new ZeroResultKeywords());

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
    @DisplayName("ES에 닿지 못하면 503 SEARCH_UNAVAILABLE이고 error 지표를 남긴다")
    void unavailable() {
        when(searcher.search(any(), anyDouble(), anyInt())).thenThrow(new IllegalStateException("ES 장애"));

        assertThatThrownBy(() -> facade.search("메이플", List.of(), null, null, null, PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(SearchException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SearchErrorCode.SEARCH_UNAVAILABLE));
        assertThat(registry.counter("hapbang.search.requests", "target", "influencer", "outcome", "error").count())
                .isEqualTo(1);
    }
}
