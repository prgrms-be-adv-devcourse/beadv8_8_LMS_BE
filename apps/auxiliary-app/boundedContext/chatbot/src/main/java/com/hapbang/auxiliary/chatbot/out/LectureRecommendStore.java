package com.hapbang.auxiliary.chatbot.out;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.hapbang.auxiliary.chatbot.domain.LectureRanker;
import com.hapbang.auxiliary.chatbot.domain.LectureSignals;
import com.hapbang.auxiliary.chatbot.domain.LectureSnapshot;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 강의 추천이 읽는 사본 조회 (#718). 강의 사본 4종을 읽기만 한다(쓰기는 {@link LectureCopyStore}).
 * 원본에 없는 행({@code missing_in_source})은 신호·제외·인기 어디에도 세지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class LectureRecommendStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final String LECTURE_COLUMNS = """
            id, seller_user_id, title, description, content_titles::text AS content_titles, on_sale, deleted, missing_in_source""";

    private final NamedParameterJdbcTemplate jdbc;

    /** 강의 ID → 강의 사본. 없는 ID는 빠진다 */
    public Map<Long, LectureSnapshot> lectures(Collection<Long> ids) {
        Map<Long, LectureSnapshot> lectures = new HashMap<>();
        if (ids.isEmpty()) {
            return lectures;
        }
        jdbc.query("SELECT " + LECTURE_COLUMNS + " FROM chatbot.lecture_copy WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids), rs -> {
                    LectureSnapshot lecture = snapshot(rs);
                    lectures.put(lecture.id(), lecture);
                });
        return lectures;
    }

    /** ID 순으로 이 ID 다음 강의 사본을 limit개 읽는다(전체 점검용) */
    public List<LectureSnapshot> lecturesAfter(long afterId, int limit) {
        return jdbc.query("SELECT " + LECTURE_COLUMNS + " FROM chatbot.lecture_copy WHERE id > :afterId ORDER BY id LIMIT :limit",
                new MapSqlParameterSource("afterId", afterId).addValue("limit", limit), (rs, row) -> snapshot(rs));
    }

    /** 회원의 구매({@code OWNED})·장바구니(삭제 안 됨) 활동 */
    public List<LectureSignals.Activity> activities(long memberId) {
        MapSqlParameterSource params = new MapSqlParameterSource("memberId", memberId);
        List<LectureSignals.Activity> activities = new ArrayList<>(jdbc.query("""
                SELECT lecture_id, COALESCE(purchased_at, ordered_at) AS at FROM chatbot.lecture_purchase_copy
                WHERE buyer_user_id = :memberId AND result = 'OWNED' AND NOT missing_in_source
                """, params, (rs, row) -> new LectureSignals.Activity(rs.getLong("lecture_id"),
                LectureSignals.Source.PURCHASED, rs.getTimestamp("at").toLocalDateTime())));
        activities.addAll(jdbc.query("""
                SELECT lecture_id, added_at FROM chatbot.cart_item_copy
                WHERE user_id = :memberId AND NOT deleted AND NOT missing_in_source
                """, params, (rs, row) -> new LectureSignals.Activity(rs.getLong("lecture_id"),
                LectureSignals.Source.CART, rs.getTimestamp("added_at").toLocalDateTime())));
        return activities;
    }

    /** 회원이 남긴 리뷰 별점(강의가 평점에 세는 리뷰만). 강의 ID → 별점 */
    public Map<Long, Integer> ratings(long memberId) {
        Map<Long, Integer> ratings = new HashMap<>();
        jdbc.query("""
                SELECT lecture_id, rating FROM chatbot.lecture_review_copy
                WHERE reviewer_user_id = :memberId AND counted AND NOT deleted AND NOT missing_in_source
                """, new MapSqlParameterSource("memberId", memberId),
                rs -> {
                    ratings.put(rs.getLong("lecture_id"), rs.getInt("rating"));
                });
        return ratings;
    }

    /** 추천에서 뺄 강의: 구매 {@code OWNED}·{@code PENDING}, 장바구니(삭제 안 됨) */
    public Set<Long> excludedLectures(long memberId) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT lecture_id FROM chatbot.lecture_purchase_copy
                WHERE buyer_user_id = :memberId AND result IN ('OWNED', 'PENDING') AND NOT missing_in_source
                UNION
                SELECT lecture_id FROM chatbot.cart_item_copy
                WHERE user_id = :memberId AND NOT deleted AND NOT missing_in_source
                """, new MapSqlParameterSource("memberId", memberId), Long.class));
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
        jdbc.query("""
                SELECT lecture_id, count(*) AS owned FROM chatbot.lecture_purchase_copy
                WHERE lecture_id IN (:ids) AND result = 'OWNED' AND NOT missing_in_source
                GROUP BY lecture_id
                """, new MapSqlParameterSource("ids", lectureIds),
                rs -> {
                    counts.put(rs.getLong("lecture_id"), rs.getLong("owned"));
                });
        return counts;
    }

    /**
     * 인기순 강의: 추천할 수 있는 상태(판매 중·삭제 안 됨·원본에 있음)이고 본인 강의와 {@code excluded}가 아닌 강의를
     * 구매 수 내림차순, 같으면 강의 ID 내림차순으로 limit개. 숨김 판매자(탈퇴·마스킹·사본 없음)는 SQL 안에서 거른 뒤 센다.
     */
    public List<LectureRanker.Popular> popular(long memberId, Collection<Long> excluded, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("memberId", memberId).addValue("limit", limit);
        String excludeClause = "";
        if (!excluded.isEmpty()) {
            excludeClause = "AND l.id NOT IN (:excluded)";
            params.addValue("excluded", excluded);
        }
        return jdbc.query("""
                SELECT l.id, l.seller_user_id,
                       count(p.order_item_id) FILTER (WHERE p.result = 'OWNED' AND NOT p.missing_in_source) AS owned
                FROM chatbot.lecture_copy l
                LEFT JOIN chatbot.lecture_purchase_copy p ON p.lecture_id = l.id
                WHERE l.on_sale AND NOT l.deleted AND NOT l.missing_in_source AND l.seller_user_id <> :memberId
                """ + "AND " + MemberCopyStore.visibleCondition("l.seller_user_id") + "\n" + excludeClause + """

                GROUP BY l.id, l.seller_user_id
                ORDER BY owned DESC, l.id DESC
                LIMIT :limit
                """, params, (rs, row) -> new LectureRanker.Popular(rs.getLong("id"), rs.getLong("seller_user_id")));
    }

    private static LectureSnapshot snapshot(ResultSet rs) throws SQLException {
        return new LectureSnapshot(rs.getLong("id"), rs.getLong("seller_user_id"), rs.getString("title"),
                rs.getString("description"), JSON.readValue(rs.getString("content_titles"), STRING_LIST),
                rs.getBoolean("on_sale"), rs.getBoolean("deleted"), rs.getBoolean("missing_in_source"));
    }
}
