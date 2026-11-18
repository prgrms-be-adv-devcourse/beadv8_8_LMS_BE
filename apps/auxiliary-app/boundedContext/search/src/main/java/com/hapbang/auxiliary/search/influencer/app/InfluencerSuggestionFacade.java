package com.hapbang.auxiliary.search.influencer.app;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.app.exception.SearchErrorCode;
import com.hapbang.auxiliary.search.app.exception.SearchException;
import com.hapbang.auxiliary.search.common.app.SearchMetrics;
import com.hapbang.auxiliary.search.influencer.app.dto.InfluencerSuggestion;
import com.hapbang.auxiliary.search.influencer.config.InfluencerSearchConfig;
import com.hapbang.auxiliary.search.influencer.out.InfluencerSearcher;

/**
 * 인플루언서 검색어 자동완성(#973, {@code GET /api/v1/search/influencers/suggestions}). 본 검색처럼 로그인 회원만 쓴다.
 * <ul>
 *     <li>검색어는 앞뒤·연속 공백을 정리하고, 비면 ES를 부르지 않고 빈 목록이다. 이때는 호출 제한도 세지 않는다.</li>
 *     <li>그다음 호출 제한을 센다. 입력할 때마다 부르므로 본 검색과 <b>따로</b> 센다(회원 ID마다 분당 120회 기본, #1012). 넘으면 429다.</li>
 *     <li>후보는 최대 {@value #SIZE}개다. 숨기지 않은 인플루언서만 닉네임 낱말 앞부분으로 찾는다({@link InfluencerSearcher#suggest}).</li>
 *     <li>ES에 닿지 못하면(처음 적재 전 포함) 503 {@code SEARCH_UNAVAILABLE}이다.</li>
 * </ul>
 * 0건 검색어는 모으지 않는다. 지표는 {@code target=influencer_suggestion}으로 본 검색과 나눠 센다.
 */
@Service
public class InfluencerSuggestionFacade {

    /** 후보 수 */
    public static final int SIZE = 5;

    private final InfluencerSearchRateLimit rateLimit;
    private final InfluencerSearcher searcher;
    private final SearchMetrics.Target metrics;

    public InfluencerSuggestionFacade(
            @Qualifier(InfluencerSearchConfig.SUGGESTION_RATE_LIMIT) InfluencerSearchRateLimit rateLimit,
            InfluencerSearcher searcher, SearchMetrics metrics) {
        this.rateLimit = rateLimit;
        this.searcher = searcher;
        this.metrics = metrics.forTarget(InfluencerSearchConfig.SUGGESTION_METRIC_TARGET);
    }

    /**
     * @param userId 부른 회원 ID. 호출 제한에만 쓴다
     */
    @PreAuthorize(SearchRoles.MEMBER)
    public List<InfluencerSuggestion> suggest(Long userId, String keyword) {
        String normalized = InfluencerSearchFacade.keyword(keyword);
        if (normalized == null) {
            return List.of();
        }
        rateLimit.acquire(userId);
        long started = System.nanoTime();
        List<InfluencerSuggestion> suggestions;
        try {
            suggestions = searcher.suggest(normalized, SIZE);
        } catch (IllegalStateException e) {
            metrics.searched("error", Duration.ofNanos(System.nanoTime() - started));
            throw new SearchException(SearchErrorCode.SEARCH_UNAVAILABLE, e);
        }
        metrics.searched(suggestions.isEmpty() ? "empty" : "ok", Duration.ofNanos(System.nanoTime() - started));
        return suggestions;
    }
}
