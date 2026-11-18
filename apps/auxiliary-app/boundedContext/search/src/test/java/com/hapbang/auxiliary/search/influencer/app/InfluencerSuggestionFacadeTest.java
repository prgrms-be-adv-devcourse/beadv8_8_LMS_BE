package com.hapbang.auxiliary.search.influencer.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.common.app.SearchMetrics;
import com.hapbang.auxiliary.search.influencer.app.dto.InfluencerSuggestion;
import com.hapbang.auxiliary.search.influencer.out.InfluencerSearcher;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 인플루언서 검색어 자동완성(#973): 한도를 세고(#1012), 빈 검색어는 ES를 부르지 않고 한도도 세지 않는다 */
class InfluencerSuggestionFacadeTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final InfluencerSearchRateLimit rateLimit = mock(InfluencerSearchRateLimit.class);
    private final InfluencerSearcher searcher = mock(InfluencerSearcher.class);
    private final InfluencerSuggestionFacade facade = new InfluencerSuggestionFacade(rateLimit, searcher,
            new SearchMetrics(registry));

    @Test
    @DisplayName("검색어를 정리해 후보를 찾고, 회원 ID로 한도를 센다. 요청 수는 자동완성 대상(influencer_suggestion)으로 센다")
    void suggests() {
        List<InfluencerSuggestion> found = List.of(new InfluencerSuggestion(1L, "메이플용사"));
        when(searcher.suggest("메이 플", InfluencerSuggestionFacade.SIZE)).thenReturn(found);

        assertThat(facade.suggest(7L, "  메이   플 ")).isEqualTo(found);

        verify(rateLimit).acquire(7L);
        assertThat(registry.counter("hapbang.search.requests", "target", "influencer_suggestion", "outcome", "ok")
                .count()).isEqualTo(1);
    }

    @Test
    @DisplayName("공백뿐인 검색어는 ES를 부르지 않고 빈 목록이다. 호출 한도도 세지 않는다")
    void blankKeyword() {
        assertThat(facade.suggest(7L, "   ")).isEmpty();

        verify(rateLimit, never()).acquire(anyLong());
        verify(searcher, never()).suggest(anyString(), anyInt());
    }
}
