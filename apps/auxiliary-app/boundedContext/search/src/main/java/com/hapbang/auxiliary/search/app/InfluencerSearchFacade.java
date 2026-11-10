package com.hapbang.auxiliary.search.app;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.app.dto.InfluencerSearchItem;
import com.hapbang.auxiliary.search.app.exception.SearchErrorCode;
import com.hapbang.auxiliary.search.app.exception.SearchException;
import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;
import com.hapbang.auxiliary.search.out.InfluencerSearcher;
import com.hapbang.auxiliary.search.out.InfluencerSearcher.SearchResult;

import lombok.RequiredArgsConstructor;

/**
 * 인플루언서 검색(#771, {@code GET /api/v1/search/influencers}). 로그인 회원만 쓴다.
 * <ul>
 *     <li>정렬은 {@code followers,desc}·{@code rating,desc} 하나만 고를 수 있고, 그 밖은 400 {@code UNSUPPORTED_SORT}다.</li>
 *     <li>앞에서 {@value #MAX_RESULT_WINDOW}건까지만 볼 수 있다({@code page × size + size}가 넘으면 400 {@code PAGE_TOO_DEEP}).
 *     전체 수도 그만큼까지만 센다.</li>
 *     <li>ES에 닿지 못하면 503 {@code SEARCH_UNAVAILABLE}이다. 회원 앱의 인플루언서 목록({@code GET /api/v1/members/influencers})은
 *     그대로 쓸 수 있다.</li>
 *     <li>필터 없이 검색어만으로 0건이면 검색어를 모은다({@link ZeroResultKeywords}). 필터가 좁아 0건인 것은 사전·동의어 문제가 아니라 세지 않는다.</li>
 * </ul>
 */
@Service
@ConditionalOnSearchEnabled
@RequiredArgsConstructor
public class InfluencerSearchFacade {

    /** 볼 수 있는 결과 범위(앞에서부터) */
    public static final int MAX_RESULT_WINDOW = 1_000;

    private final InfluencerSearcher searcher;
    private final RatingBaseline ratingBaseline;
    private final SearchMetrics metrics;
    private final ZeroResultKeywords zeroResultKeywords;

    /**
     * @param userId 검색한 회원 ID. 0건 검색어를 "서로 다른 회원 몇 명이 찾았는지" 세는 데만 쓰고 남기지 않는다
     */
    @PreAuthorize(SearchRoles.MEMBER)
    public Page<InfluencerSearchItem> search(Long userId, String keyword, List<String> contentCategories, Boolean verified,
            Long minFollowers, Double minRating, Pageable pageable) {
        InfluencerSort sort = sort(pageable.getSort());
        if (pageable.getOffset() + pageable.getPageSize() > MAX_RESULT_WINDOW) {
            throw new SearchException(SearchErrorCode.PAGE_TOO_DEEP);
        }
        InfluencerSearchCondition condition = new InfluencerSearchCondition(keyword(keyword), codes(contentCategories),
                verified, minFollowers, minRating, sort, pageable.getPageNumber(), pageable.getPageSize());

        long started = System.nanoTime();
        SearchResult result;
        try {
            double baseline = sort == InfluencerSort.RATING ? ratingBaseline.get() : 0;
            // 지표는 검색 쿼리 시간만 잰다(평균 C 집계는 뺀다)
            started = System.nanoTime();
            result = searcher.search(condition, baseline, MAX_RESULT_WINDOW);
        } catch (IllegalStateException e) {
            metrics.influencerSearched("error", Duration.ofNanos(System.nanoTime() - started));
            throw new SearchException(SearchErrorCode.SEARCH_UNAVAILABLE, e);
        }
        metrics.influencerSearched(result.total() == 0 ? "empty" : "ok", Duration.ofNanos(System.nanoTime() - started));
        if (result.total() == 0 && condition.keyword() != null && !hasFilter(condition)) {
            metrics.influencerZeroResult();
            zeroResultKeywords.record(condition.keyword(), userId);
        }
        return new PageImpl<>(result.items(), PageRequest.of(condition.page(), condition.size()), result.total());
    }

    /** 검색어 밖의 조건(분야·인증·팔로워·평점)이 있는지 */
    static boolean hasFilter(InfluencerSearchCondition condition) {
        return !condition.contentCategories().isEmpty() || condition.verified() != null
                || condition.minFollowers() != null || condition.minRating() != null;
    }

    /** 고를 수 있는 정렬: 없음(기본), followers,desc, rating,desc */
    static InfluencerSort sort(Sort sort) {
        if (sort.isUnsorted()) {
            return InfluencerSort.DEFAULT;
        }
        List<Sort.Order> orders = sort.toList();
        Sort.Order order = orders.getFirst();
        if (orders.size() == 1 && order.isDescending()) {
            if ("followers".equals(order.getProperty())) {
                return InfluencerSort.FOLLOWERS;
            }
            if ("rating".equals(order.getProperty())) {
                return InfluencerSort.RATING;
            }
        }
        throw new SearchException(SearchErrorCode.UNSUPPORTED_SORT);
    }

    /** 앞뒤·연속 공백을 정리한다. 비면 null(검색어 없음) */
    static String keyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.strip().replaceAll("\\s+", " ");
    }

    /** 빈 값을 빼고 중복을 지운다 */
    static List<String> codes(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        return codes.stream().filter(Objects::nonNull).map(String::strip).filter(code -> !code.isEmpty()).distinct()
                .toList();
    }
}
