package com.hapbang.auxiliary.search.lecture.app;

import java.time.Duration;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.app.exception.SearchErrorCode;
import com.hapbang.auxiliary.search.app.exception.SearchException;
import com.hapbang.auxiliary.search.common.app.SearchMetrics;
import com.hapbang.auxiliary.search.lecture.app.dto.LectureSearchItem;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexDefinition;
import com.hapbang.auxiliary.search.lecture.out.LectureSearcher;
import com.hapbang.auxiliary.search.lecture.out.LectureSearcher.SearchResult;

/**
 * 강의 검색(#883, {@code GET /api/v1/search/lectures}). 비회원도 쓴다(L2, 강의 목록과 같다).
 * <ul>
 *     <li>먼저 호출 제한을 센다({@link LectureSearchRateLimit}, 회원 ID·client IP 각 분당 60회). 넘으면 429다. 잘못된 요청도 센다.</li>
 *     <li>정렬은 {@code latest,desc}·{@code rating,desc} 하나만 고를 수 있고, 그 밖은 400 {@code UNSUPPORTED_SORT}다(L10, Q1).</li>
 *     <li>앞에서 {@value #MAX_RESULT_WINDOW}건까지만 볼 수 있다({@code page × size + size}가 넘으면 400 {@code PAGE_TOO_DEEP}).
 *     전체 수도 그만큼까지만 센다.</li>
 *     <li>ES에 닿지 못하면(처음 적재 전이라 alias가 없을 때 포함) 503 {@code SEARCH_UNAVAILABLE}이다. 강의 앱의 강의 목록
 *     ({@code GET /api/v1/lectures})은 그대로 쓸 수 있다.</li>
 *     <li>검색어로 0건이면 검색어를 모은다({@link LectureZeroResultKeywords}, L19). 회원은 회원 ID, 비회원은 client IP 해시로 서로 다른 검색자를
 *     센다. 강의 검색에는 필터가 없어 검색어가 있는 0건은 모두 센다.</li>
 * </ul>
 */
@Service
public class LectureSearchFacade {

    /** 볼 수 있는 결과 범위(앞에서부터, 인플루언서와 같다) */
    public static final int MAX_RESULT_WINDOW = 1_000;

    static final String UNSUPPORTED_SORT_DETAIL = "지원하지 않는 정렬입니다. latest,desc 또는 rating,desc만 쓸 수 있습니다.";

    private final LectureSearchRateLimit rateLimit;
    private final LectureSearcher searcher;
    private final LectureRatingBaseline ratingBaseline;
    private final SearchMetrics.Target metrics;
    private final LectureZeroResultKeywords zeroResultKeywords;

    public LectureSearchFacade(LectureSearchRateLimit rateLimit, LectureSearcher searcher,
            LectureRatingBaseline ratingBaseline, SearchMetrics metrics, LectureZeroResultKeywords zeroResultKeywords) {
        this.rateLimit = rateLimit;
        this.searcher = searcher;
        this.ratingBaseline = ratingBaseline;
        this.metrics = metrics.forTarget(LectureIndexDefinition.METRIC_TARGET);
        this.zeroResultKeywords = zeroResultKeywords;
    }

    /**
     * @param requester 검색한 사람. 호출 제한과, 0건 검색어를 "서로 다른 검색자 몇 명이 찾았는지" 세는 데만 쓰고 남기지 않는다
     */
    public Page<LectureSearchItem> search(Requester requester, String keyword, Pageable pageable) {
        rateLimit.acquire(requester);
        LectureSort sort = sort(pageable.getSort());
        if (pageable.getOffset() + pageable.getPageSize() > MAX_RESULT_WINDOW) {
            throw new SearchException(SearchErrorCode.PAGE_TOO_DEEP);
        }
        LectureSearchCondition condition = new LectureSearchCondition(keyword(keyword), sort, pageable.getPageNumber(),
                pageable.getPageSize());

        long started = System.nanoTime();
        SearchResult result;
        try {
            // 평균 C는 평점 가산(검색어)과 평점순에만 쓴다. 최신순 목록은 C를 구하지 않는다
            double baseline = needsBaseline(condition) ? ratingBaseline.get() : 0;
            // 지표는 검색 쿼리 시간만 잰다(평균 C 집계는 뺀다)
            started = System.nanoTime();
            result = searcher.search(condition, baseline, MAX_RESULT_WINDOW);
        } catch (IllegalStateException e) {
            metrics.searched("error", Duration.ofNanos(System.nanoTime() - started));
            throw new SearchException(SearchErrorCode.SEARCH_UNAVAILABLE, e);
        }
        metrics.searched(result.total() == 0 ? "empty" : "ok", Duration.ofNanos(System.nanoTime() - started));
        if (result.total() == 0 && condition.keyword() != null) {
            metrics.zeroResult();
            recordZeroResult(condition.keyword(), requester);
        }
        return new PageImpl<>(result.items(), PageRequest.of(condition.page(), condition.size()), result.total());
    }

    private void recordZeroResult(String keyword, Requester requester) {
        if (requester.userId() != null) {
            zeroResultKeywords.record(keyword, requester.userId());
        } else if (requester.clientIpKey() != null) {
            zeroResultKeywords.record(keyword, requester.clientIpKey());
        }
        // 공인 주소가 아닌 비회원(경로가 깨진 신호, Q2)은 검색자를 구분할 수 없어 세지 않는다
    }

    static boolean needsBaseline(LectureSearchCondition condition) {
        return condition.sort() == LectureSort.RATING
                || (condition.sort() == LectureSort.DEFAULT && condition.keyword() != null);
    }

    /** 고를 수 있는 정렬: 없음(기본), latest,desc, rating,desc */
    static LectureSort sort(Sort sort) {
        if (sort.isUnsorted()) {
            return LectureSort.DEFAULT;
        }
        List<Sort.Order> orders = sort.toList();
        Sort.Order order = orders.getFirst();
        if (orders.size() == 1 && order.isDescending()) {
            if ("latest".equals(order.getProperty())) {
                return LectureSort.LATEST;
            }
            if ("rating".equals(order.getProperty())) {
                return LectureSort.RATING;
            }
        }
        throw new SearchException(SearchErrorCode.UNSUPPORTED_SORT, UNSUPPORTED_SORT_DETAIL);
    }

    /** 앞뒤·연속 공백을 정리한다. 비면 null(검색어 없음) */
    static String keyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.strip().replaceAll("\\s+", " ");
    }
}
