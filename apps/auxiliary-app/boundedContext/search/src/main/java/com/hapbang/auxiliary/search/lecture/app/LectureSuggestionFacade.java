package com.hapbang.auxiliary.search.lecture.app;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.app.exception.SearchErrorCode;
import com.hapbang.auxiliary.search.app.exception.SearchException;
import com.hapbang.auxiliary.search.common.app.SearchMetrics;
import com.hapbang.auxiliary.search.lecture.app.dto.LectureSuggestion;
import com.hapbang.auxiliary.search.lecture.config.LectureSearchConfig;
import com.hapbang.auxiliary.search.lecture.out.LectureSearcher;

/**
 * 강의 검색어 자동완성(#973, {@code GET /api/v1/search/lectures/suggestions}). 비회원도 쓴다(본 검색과 같다).
 * <ul>
 *     <li>먼저 호출 제한을 센다. 입력할 때마다 부르므로 본 검색과 <b>따로</b> 센다(회원 ID·client IP 각 분당 120회 기본). 넘으면 429다.</li>
 *     <li>검색어는 본 검색처럼 앞뒤·연속 공백을 정리하고, 비면 ES를 부르지 않고 빈 목록이다(입력창을 지울 때마다 오류가 나지 않게).</li>
 *     <li>후보는 최대 {@value #SIZE}개다. 보일 강의만 낱말 앞부분으로 찾는다({@link LectureSearcher#suggest}).</li>
 *     <li>ES에 닿지 못하면(처음 적재 전 포함) 503 {@code SEARCH_UNAVAILABLE}이다. FE는 후보를 숨기면 된다.</li>
 * </ul>
 * 0건 검색어는 모으지 않는다(치는 도중의 말이라 사전 근거가 아니다). 지표는 {@code target=lecture_suggestion}으로 본 검색과 나눠 센다.
 */
@Service
public class LectureSuggestionFacade {

    /** 후보 수 */
    public static final int SIZE = 5;

    private final LectureSearchRateLimit rateLimit;
    private final LectureSearcher searcher;
    private final SearchMetrics.Target metrics;

    public LectureSuggestionFacade(@Qualifier(LectureSearchConfig.SUGGESTION_RATE_LIMIT) LectureSearchRateLimit rateLimit,
            LectureSearcher searcher, SearchMetrics metrics) {
        this.rateLimit = rateLimit;
        this.searcher = searcher;
        this.metrics = metrics.forTarget(LectureSearchConfig.SUGGESTION_METRIC_TARGET);
    }

    /**
     * @param requester 부른 사람. 호출 제한에만 쓴다
     */
    public List<LectureSuggestion> suggest(Requester requester, String keyword) {
        rateLimit.acquire(requester);
        String normalized = LectureSearchFacade.keyword(keyword);
        if (normalized == null) {
            return List.of();
        }
        long started = System.nanoTime();
        List<LectureSuggestion> suggestions;
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
