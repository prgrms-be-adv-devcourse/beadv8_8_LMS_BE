package com.hapbang.auxiliary.chatbot.out;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.hapbang.auxiliary.chatbot.domain.InfluencerPopularity;
import com.hapbang.auxiliary.chatbot.domain.InfluencerProfile;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

import lombok.RequiredArgsConstructor;

/**
 * 인플루언서 추천이 읽는 사본 (#719). 쓰지 않는다. 트랜잭션은 열지 않는다(문장 하나씩, 추천 사이에 ES 호출이 있다).
 * <p>
 * 추천 가능 규칙은 {@link InfluencerProfile#recommendable()}과 같은 내용을 SQL로도 쓴다({@link #RECOMMENDABLE}). 둘을 함께 고친다.
 */
@Repository
@RequiredArgsConstructor
public class InfluencerRecommendStore {

    // InfluencerProfile.recommendable과 같은 규칙. 별칭 m은 member_copy
    private static final String RECOMMENDABLE = """
            m.role IN ('INFLUENCER', 'VERIFIED_INFLUENCER') AND m.status = 'ACTIVE'
              AND NOT EXISTS (SELECT 1 FROM chatbot.masked_member x WHERE x.user_id = m.id)
            """;

    private static final String PROFILES = """
            SELECT m.id, m.role, m.status, m.bio,
                   ARRAY(SELECT c FROM jsonb_array_elements_text(m.categories) WITH ORDINALITY AS t(c, n) ORDER BY n)
                       AS categories,
                   EXISTS (SELECT 1 FROM chatbot.masked_member x WHERE x.user_id = m.id) AS masked
            FROM chatbot.member_copy m
            WHERE m.id IN (:ids)
            """;

    // 함께 마친 출연의 상대(원본 없음 제외)
    private static final String COMPLETED_PARTNERS = """
            SELECT DISTINCT CASE WHEN a.host_id = :userId THEN a.guest_id ELSE a.host_id END
            FROM chatbot.appearance_copy a
            WHERE a.result = 'COMPLETED' AND NOT a.missing_in_source AND a.host_id <> a.guest_id
              AND (a.host_id = :userId OR a.guest_id = :userId)
            """;

    // 완료 출연 수. 호스트·게스트 모두 센다. 자기 자신과의 출연(있을 수 없지만)은 세지 않는다. %s는 회원 조건
    private static final String COMPLETED_COUNTS = """
            SELECT p.member_id, count(*) AS completed
            FROM chatbot.appearance_copy a
            CROSS JOIN LATERAL (VALUES (a.host_id), (a.guest_id)) AS p(member_id)
            JOIN chatbot.member_copy m ON m.id = p.member_id
            WHERE a.result = 'COMPLETED' AND NOT a.missing_in_source AND a.host_id <> a.guest_id AND %s
            GROUP BY p.member_id
            """;

    // 받은 출연 리뷰. 리뷰나 그 출연이 원본 없음이면 뺀다. 출연 사본이 아직 없는 리뷰는 센다(사본 사이에 순서 보장이 없다)
    private static final String REVIEWS = """
            FROM chatbot.appearance_review_copy r
            LEFT JOIN chatbot.appearance_copy a ON a.id = r.appearance_id
            WHERE NOT r.missing_in_source AND (a.id IS NULL OR NOT a.missing_in_source)
            """;

    // 받은 리뷰 수·별점 합. %s는 회원 조건
    private static final String REVIEW_SUMS = "SELECT r.reviewee_user_id AS member_id, count(*) AS reviews, "
            + "sum(r.rating) AS rating_sum " + REVIEWS
            + " AND EXISTS (SELECT 1 FROM chatbot.member_copy m WHERE m.id = r.reviewee_user_id AND %s)"
            + " GROUP BY r.reviewee_user_id";

    private static final String IN_IDS = "m.id IN (:ids)";

    private final NamedParameterJdbcTemplate jdbc;

    /** 회원 사본의 프로필. 사본이 없는 ID는 빠진다 */
    public Map<Long, InfluencerProfile> profiles(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, InfluencerProfile> profiles = new HashMap<>();
        jdbc.query(PROFILES, new MapSqlParameterSource("ids", ids), rs -> {
            InfluencerProfile profile = toProfile(rs);
            profiles.put(profile.id(), profile);
        });
        return profiles;
    }

    /** 이 ID보다 큰 회원 사본 ID를 오름차순으로 size개(정합 점검이 모든 회원을 훑는다) */
    public List<Long> memberIdsAfter(long afterId, int size) {
        return jdbc.queryForList("SELECT id FROM chatbot.member_copy WHERE id > :afterId ORDER BY id LIMIT :size",
                new MapSqlParameterSource().addValue("afterId", afterId).addValue("size", size), Long.class);
    }

    /** 주어진 회원 중 추천 가능한 회원 */
    public Set<Long> recommendableOf(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT m.id FROM chatbot.member_copy m WHERE " + IN_IDS + " AND " + RECOMMENDABLE,
                new MapSqlParameterSource("ids", ids), Long.class));
    }

    /** 이 회원과 출연을 함께 마친 상대(원본 없음 제외, 본인 제외) */
    public Set<Long> completedPartners(long userId) {
        return new LinkedHashSet<>(jdbc.queryForList(COMPLETED_PARTNERS, new MapSqlParameterSource("userId", userId),
                Long.class));
    }

    /** 주어진 회원들의 인기 재료. 출연·리뷰가 없는 회원도 0으로 들어간다(주어진 순서) */
    public List<InfluencerPopularity.Stats> popularityStats(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return stats(IN_IDS, new MapSqlParameterSource("ids", ids), new LinkedHashSet<>(ids));
    }

    /**
     * 추천 가능한 모든 회원의 인기 재료(인기순 채우기). 회원 ID를 넘기지 않고 SQL 조건으로 고른다(수천 명이어도 바인드 수가 늘지 않는다).
     * 회원 ID 오름차순.
     */
    public List<InfluencerPopularity.Stats> popularityStatsOfRecommendable() {
        List<Long> ids = jdbc.queryForList("SELECT m.id FROM chatbot.member_copy m WHERE " + RECOMMENDABLE + " ORDER BY m.id",
                new MapSqlParameterSource(), Long.class);
        return stats(RECOMMENDABLE, new MapSqlParameterSource(), new LinkedHashSet<>(ids));
    }

    /** 모든 받은 출연 리뷰의 평균 별점(리뷰가 없으면 0) */
    public double overallMeanRating() {
        Double mean = jdbc.queryForObject("SELECT coalesce(avg(r.rating), 0) " + REVIEWS, new MapSqlParameterSource(),
                Double.class);
        return mean == null ? 0 : mean;
    }

    // memberCondition: 별칭 m(member_copy)에 거는 조건
    private List<InfluencerPopularity.Stats> stats(String memberCondition, MapSqlParameterSource params, Set<Long> ids) {
        Map<Long, Long> completed = new HashMap<>();
        jdbc.query(COMPLETED_COUNTS.formatted(memberCondition), params, rs -> {
            completed.put(rs.getLong("member_id"), rs.getLong("completed"));
        });
        Map<Long, ReviewSum> reviews = new HashMap<>();
        jdbc.query(REVIEW_SUMS.formatted(memberCondition), params, rs -> {
            reviews.put(rs.getLong("member_id"), new ReviewSum(rs.getLong("reviews"), rs.getLong("rating_sum")));
        });
        List<InfluencerPopularity.Stats> stats = new ArrayList<>();
        for (Long id : ids) {
            ReviewSum review = reviews.getOrDefault(id, ReviewSum.NONE);
            stats.add(new InfluencerPopularity.Stats(id, completed.getOrDefault(id, 0L), review.count(), review.sum()));
        }
        return stats;
    }

    private static InfluencerProfile toProfile(ResultSet rs) throws SQLException {
        Array array = rs.getArray("categories");
        List<String> categories = array == null ? List.of() : List.of((String[]) array.getArray());
        return new InfluencerProfile(rs.getLong("id"), Role.valueOf(rs.getString("role")),
                UserStatus.valueOf(rs.getString("status")), categories, rs.getString("bio"), rs.getBoolean("masked"));
    }

    private record ReviewSum(long count, long sum) {

        static final ReviewSum NONE = new ReviewSum(0, 0);
    }
}
