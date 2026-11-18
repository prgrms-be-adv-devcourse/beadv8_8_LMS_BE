package com.hapbang.auxiliary.chatbot.out;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

import com.hapbang.auxiliary.chatbot.domain.LectureListing;
import com.hapbang.auxiliary.chatbot.domain.LectureRanker;
import com.hapbang.auxiliary.chatbot.domain.LectureSignals;
import com.hapbang.auxiliary.chatbot.domain.LectureSnapshot;
import com.hapbang.shared.lecture.domain.LecturePurchaseResult;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 강의 추천이 읽는 사본 조회 (#718). 강의 사본 4종을 읽기만 한다(쓰기는 {@link LectureCopyStore}).
 * 원본에 없는 행({@code missing_in_source})은 신호·제외·인기 어디에도 세지 않는다.
 * <p>
 * JDBC로 쓰는 이유: 사본 테이블은 엔티티 없이 다루고, {@code count(...) FILTER}·{@code ::text}·배열 비교({@code <> ALL})
 * 같은 PostgreSQL 문법을 쓴다. QueryDSL은 사본 테이블에 엔티티를 두게 되면 다시 본다(#1003).
 * SQL은 문장마다 상수 하나로 두고 실행 중에 이어 붙이지 않는다. 구매 상태 값은 {@link LecturePurchaseResult}의 이름을 파라미터로 넘긴다({@link #resultParams()}).
 */
@Repository
@RequiredArgsConstructor
public class LectureRecommendStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    // 색인용 강의 사본 열(콘텐츠 제목 포함). 아래 두 문장이 함께 쓴다
    private static final String LECTURE_SELECT = """
            SELECT id, seller_user_id, title, description, content_titles::text AS content_titles, on_sale, deleted,
                   missing_in_source
            FROM chatbot.lecture_copy
            """;

    private static final String SELECT_LECTURES = """
            %s\
            WHERE id IN (:ids)
            """.formatted(LECTURE_SELECT);

    private static final String SELECT_LECTURES_AFTER = """
            %s\
            WHERE id > :afterId
            ORDER BY id
            LIMIT :limit
            """.formatted(LECTURE_SELECT);

    // 판매자 보임 규칙은 MemberCopyStore 한 곳에 둔다. 클래스를 읽을 때 한 번 끼운다
    private static final String SELECT_LISTINGS = """
            SELECT l.id, l.seller_user_id, l.title, l.description, l.on_sale, l.deleted, l.missing_in_source,
                   %s AS seller_visible
            FROM chatbot.lecture_copy l
            WHERE l.id IN (:ids)
            """.formatted(MemberCopyStore.visibleCondition("l.seller_user_id"));

    private static final String SELECT_PURCHASES = """
            SELECT lecture_id, result, COALESCE(purchased_at, ordered_at) AS at
            FROM chatbot.lecture_purchase_copy
            WHERE buyer_user_id = :memberId AND result IN (:owned, :pending) AND NOT missing_in_source
            """;

    private static final String SELECT_CART_ITEMS = """
            SELECT lecture_id, added_at
            FROM chatbot.cart_item_copy
            WHERE user_id = :memberId AND NOT deleted AND NOT missing_in_source
            """;

    private static final String SELECT_RATINGS = """
            SELECT lecture_id, rating
            FROM chatbot.lecture_review_copy
            WHERE reviewer_user_id = :memberId AND counted AND NOT deleted AND NOT missing_in_source
            """;

    private static final String SELECT_OWNED_COUNTS = """
            SELECT lecture_id, count(*) AS owned
            FROM chatbot.lecture_purchase_copy
            WHERE lecture_id IN (:ids) AND result = :owned AND NOT missing_in_source
            GROUP BY lecture_id
            """;

    // :memberId가 null이면 본인 강의 조건을 건너뛴다. :excluded는 bigint 배열이라 비어 있어도 된다(빈 IN은 문법 오류)
    private static final String SELECT_POPULAR = """
            SELECT l.id, l.seller_user_id,
                   count(p.order_item_id) FILTER (WHERE p.result = :owned AND NOT p.missing_in_source) AS owned
            FROM chatbot.lecture_copy l
            LEFT JOIN chatbot.lecture_purchase_copy p ON p.lecture_id = l.id
            WHERE l.on_sale AND NOT l.deleted AND NOT l.missing_in_source
              AND %s
              AND (CAST(:memberId AS bigint) IS NULL OR l.seller_user_id <> :memberId)
              AND l.id <> ALL(:excluded)
            GROUP BY l.id, l.seller_user_id
            ORDER BY owned DESC, l.id DESC
            LIMIT :limit
            """.formatted(MemberCopyStore.visibleCondition("l.seller_user_id"));

    private static final RowMapper<LectureSnapshot> SNAPSHOT_MAPPER = (rs, row) -> new LectureSnapshot(
            rs.getLong("id"),
            rs.getLong("seller_user_id"),
            rs.getString("title"),
            rs.getString("description"),
            JSON.readValue(rs.getString("content_titles"), STRING_LIST),
            rs.getBoolean("on_sale"),
            rs.getBoolean("deleted"),
            rs.getBoolean("missing_in_source"));

    private static final RowMapper<LectureListing> LISTING_MAPPER = (rs, row) -> new LectureListing(
            rs.getLong("id"),
            rs.getLong("seller_user_id"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getBoolean("on_sale"),
            rs.getBoolean("deleted"),
            rs.getBoolean("missing_in_source"),
            rs.getBoolean("seller_visible"));

    private static final RowMapper<Purchase> PURCHASE_MAPPER = (rs, row) -> new Purchase(
            rs.getLong("lecture_id"),
            LecturePurchaseResult.valueOf(rs.getString("result")),
            rs.getTimestamp("at").toLocalDateTime());

    private static final RowMapper<LectureSignals.Activity> CART_ACTIVITY_MAPPER = (rs, row) ->
            new LectureSignals.Activity(rs.getLong("lecture_id"), LectureSignals.Source.CART,
                    rs.getTimestamp("added_at").toLocalDateTime());

    private static final RowMapper<Map.Entry<Long, Integer>> RATING_MAPPER = (rs, row) ->
            Map.entry(rs.getLong("lecture_id"), rs.getInt("rating"));

    private static final RowMapper<Map.Entry<Long, Long>> OWNED_COUNT_MAPPER = (rs, row) ->
            Map.entry(rs.getLong("lecture_id"), rs.getLong("owned"));

    private static final RowMapper<LectureRanker.Popular> POPULAR_MAPPER = (rs, row) ->
            new LectureRanker.Popular(rs.getLong("id"), rs.getLong("seller_user_id"));

    private final NamedParameterJdbcTemplate jdbc;

    /** 강의 ID → 강의 사본(색인용, 콘텐츠 제목 포함). 없는 ID는 빠진다 */
    public Map<Long, LectureSnapshot> lectures(Collection<Long> ids) {
        Map<Long, LectureSnapshot> lectures = new HashMap<>();
        if (ids.isEmpty()) {
            return lectures;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
        for (LectureSnapshot lecture : jdbc.query(SELECT_LECTURES, params, SNAPSHOT_MAPPER)) {
            lectures.put(lecture.id(), lecture);
        }
        return lectures;
    }

    /** 강의 ID → 추천이 쓰는 강의 사본(콘텐츠 제목 없이)과 판매자 보임. 없는 ID는 빠진다 */
    public Map<Long, LectureListing> listings(Collection<Long> ids) {
        Map<Long, LectureListing> listings = new HashMap<>();
        if (ids.isEmpty()) {
            return listings;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
        for (LectureListing listing : jdbc.query(SELECT_LISTINGS, params, LISTING_MAPPER)) {
            listings.put(listing.id(), listing);
        }
        return listings;
    }

    /** ID 순으로 이 ID 다음 강의 사본을 limit개 읽는다(전체 점검용) */
    public List<LectureSnapshot> lecturesAfter(long afterId, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("afterId", afterId).addValue("limit", limit);
        return jdbc.query(SELECT_LECTURES_AFTER, params, SNAPSHOT_MAPPER);
    }

    /**
     * 회원의 구매·장바구니를 한 번씩 읽어 추천에서 뺄 강의와 취향 활동을 함께 만든다.
     * <ul>
     *     <li>뺄 강의: 구매 {@code OWNED}·{@code PENDING}, 장바구니(삭제 안 됨)</li>
     *     <li>활동: 구매 {@code OWNED}(시각은 구매 확정, 없으면 주문), 장바구니(삭제 안 됨). 구매 다음에 장바구니 순서</li>
     * </ul>
     */
    public MemberLectures memberLectures(long memberId) {
        MapSqlParameterSource params = resultParams().addValue("memberId", memberId);
        Set<Long> excluded = new HashSet<>();
        List<LectureSignals.Activity> activities = new ArrayList<>();
        for (Purchase purchase : jdbc.query(SELECT_PURCHASES, params, PURCHASE_MAPPER)) {
            excluded.add(purchase.lectureId());
            if (purchase.result() == LecturePurchaseResult.OWNED) {
                activities.add(new LectureSignals.Activity(purchase.lectureId(), LectureSignals.Source.PURCHASED,
                        purchase.at()));
            }
        }
        for (LectureSignals.Activity cart : jdbc.query(SELECT_CART_ITEMS, params, CART_ACTIVITY_MAPPER)) {
            excluded.add(cart.lectureId());
            activities.add(cart);
        }
        return new MemberLectures(excluded, activities);
    }

    /**
     * 회원의 구매·장바구니로 만든 값.
     *
     * @param excluded   추천에서 뺄 강의
     * @param activities 취향 신호가 되는 활동
     */
    public record MemberLectures(Set<Long> excluded, List<LectureSignals.Activity> activities) {
    }

    /** 회원이 남긴 리뷰 별점(강의가 평점에 세는 리뷰만). 강의 ID → 별점 */
    public Map<Long, Integer> ratings(long memberId) {
        Map<Long, Integer> ratings = new HashMap<>();
        MapSqlParameterSource params = new MapSqlParameterSource("memberId", memberId);
        for (Map.Entry<Long, Integer> rating : jdbc.query(SELECT_RATINGS, params, RATING_MAPPER)) {
            ratings.put(rating.getKey(), rating.getValue());
        }
        return ratings;
    }

    /** 강의별 구매({@code OWNED}) 수. 구매가 없는 강의는 0 */
    public Map<Long, Long> ownedCounts(Collection<Long> lectureIds) {
        Map<Long, Long> counts = new HashMap<>();
        for (Long id : lectureIds) {
            counts.put(id, 0L);
        }
        if (lectureIds.isEmpty()) {
            return counts;
        }
        MapSqlParameterSource params = resultParams().addValue("ids", lectureIds);
        for (Map.Entry<Long, Long> count : jdbc.query(SELECT_OWNED_COUNTS, params, OWNED_COUNT_MAPPER)) {
            counts.put(count.getKey(), count.getValue());
        }
        return counts;
    }

    /**
     * 인기순 강의: 추천할 수 있는 상태(판매 중·삭제 안 됨·원본에 있음)이고 본인 강의와 {@code excluded}가 아닌 강의를
     * 구매 수 내림차순, 같으면 강의 ID 내림차순으로 limit개. 숨김 판매자(탈퇴·마스킹·사본 없음)는 SQL 안에서 거른 뒤 센다.
     *
     * @param memberId 요청 회원(본인 강의를 뺀다). null이면 회원과 상관없는 목록(인기순 캐시)
     */
    public List<LectureRanker.Popular> popular(@Nullable Long memberId, Collection<Long> excluded, int limit) {
        MapSqlParameterSource params = resultParams()
                .addValue("limit", limit)
                .addValue("memberId", memberId, Types.BIGINT)
                .addValue("excluded", new SqlArrayValue("bigint", excluded.toArray()));
        return jdbc.query(SELECT_POPULAR, params, POPULAR_MAPPER);
    }

    // SQL의 구매 상태 값(:owned, :pending). 쓰지 않는 문장에 넘겨도 무시된다
    private static MapSqlParameterSource resultParams() {
        return new MapSqlParameterSource("owned", LecturePurchaseResult.OWNED.name())
                .addValue("pending", LecturePurchaseResult.PENDING.name());
    }

    // 구매 한 행. 모두 뺄 강의이고, OWNED면 활동
    private record Purchase(long lectureId, LecturePurchaseResult result, LocalDateTime at) {
    }
}
