package com.hapbang.auxiliary.chatbot.out;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.hapbang.auxiliary.chatbot.domain.InfluencerPopularity;
import com.hapbang.auxiliary.chatbot.domain.InfluencerProfile;
import com.hapbang.shared.appearance.domain.AppearanceResult;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

import lombok.RequiredArgsConstructor;

/**
 * 인플루언서 추천이 읽는 사본 (#719). 쓰지 않는다. 트랜잭션은 열지 않는다(문장 하나씩, 추천 사이에 ES 호출이 있다).
 * <p>
 * 추천 가능 규칙은 {@link InfluencerProfile#recommendable()}과 같은 내용을 SQL로도 쓴다({@link #RECOMMENDABLE}). 둘을 함께 고친다.
 * <p>
 * JDBC로 쓰는 이유: 사본 테이블은 엔티티 없이 다루고, {@code jsonb_array_elements_text}·{@code ARRAY(...)}·{@code LATERAL} 같은
 * PostgreSQL 문법을 쓴다. QueryDSL은 사본 테이블에 엔티티를 두게 되면 다시 본다(#1003).
 * SQL은 문장마다 상수 하나로 두고 실행 중에 이어 붙이지 않는다. 같은 조각은 클래스를 읽을 때 한 번 끼운다.
 * 역할·상태·출연 결과 값은 enum 이름을 파라미터로 넘긴다({@link #params()}).
 */
@Repository
@RequiredArgsConstructor
public class InfluencerRecommendStore {

    // InfluencerProfile.recommendable과 같은 규칙. 별칭 m은 member_copy
    private static final String RECOMMENDABLE = """
            m.role IN (:influencerRoles) AND m.status = :active
              AND NOT EXISTS (SELECT 1 FROM chatbot.masked_member x WHERE x.user_id = m.id)""";

    private static final String PROFILE_SELECT = """
            SELECT m.id, m.role, m.status, m.bio,
                   ARRAY(SELECT c FROM jsonb_array_elements_text(m.categories) WITH ORDINALITY AS t(c, n) ORDER BY n)
                       AS categories,
                   EXISTS (SELECT 1 FROM chatbot.masked_member x WHERE x.user_id = m.id) AS masked
            FROM chatbot.member_copy m
            """;

    private static final String PROFILES = """
            %s\
            WHERE m.id IN (:ids)
            """.formatted(PROFILE_SELECT);

    private static final String RECOMMENDABLE_PROFILES = """
            %s\
            WHERE m.id IN (:ids) AND %s
            """.formatted(PROFILE_SELECT, RECOMMENDABLE);

    private static final String PROFILES_AFTER = """
            %s\
            WHERE m.id > :afterId
            ORDER BY m.id
            LIMIT :size
            """.formatted(PROFILE_SELECT);

    // 함께 마친 출연의 상대(원본 없음 제외)
    private static final String COMPLETED_PARTNERS = """
            SELECT DISTINCT CASE WHEN a.host_id = :userId THEN a.guest_id ELSE a.host_id END
            FROM chatbot.appearance_copy a
            WHERE a.result = :completed AND NOT a.missing_in_source AND a.host_id <> a.guest_id
              AND (a.host_id = :userId OR a.guest_id = :userId)
            """;

    // 받은 출연 리뷰. 리뷰나 그 출연이 원본 없음이면 뺀다. 출연 사본이 아직 없는 리뷰는 센다(사본 사이에 순서 보장이 없다)
    private static final String COUNTED_REVIEWS = """
            FROM chatbot.appearance_review_copy r
            LEFT JOIN chatbot.appearance_copy a ON a.id = r.appearance_id
            WHERE NOT r.missing_in_source AND (a.id IS NULL OR NOT a.missing_in_source)
            """;

    private static final String OVERALL_MEAN_RATING = """
            SELECT coalesce(avg(r.rating), 0)
            %s""".formatted(COUNTED_REVIEWS);

    /*
     * 회원마다 완료 출연 수·받은 리뷰 수·별점 합을 한 문장으로. 회원 ID 오름차순.
     * 완료 출연은 호스트·게스트 모두 세고, 자기 자신과의 출연(있을 수 없지만)은 세지 않는다.
     * 세 %1$s 모두 같은 회원 조건(별칭 m), %2$s는 받은 리뷰 조건.
     */
    private static final String STATS = """
            SELECT m.id, coalesce(c.completed, 0) AS completed, coalesce(r.reviews, 0) AS reviews,
                   coalesce(r.rating_sum, 0) AS rating_sum
            FROM chatbot.member_copy m
            LEFT JOIN (
                SELECT p.member_id, count(*) AS completed
                FROM chatbot.appearance_copy a
                CROSS JOIN LATERAL (VALUES (a.host_id), (a.guest_id)) AS p(member_id)
                JOIN chatbot.member_copy m ON m.id = p.member_id
                WHERE a.result = :completed AND NOT a.missing_in_source AND a.host_id <> a.guest_id AND %1$s
                GROUP BY p.member_id
            ) c ON c.member_id = m.id
            LEFT JOIN (
                SELECT r.reviewee_user_id AS member_id, count(*) AS reviews, sum(r.rating) AS rating_sum
                %2$s
                  AND EXISTS (SELECT 1 FROM chatbot.member_copy m WHERE m.id = r.reviewee_user_id AND %1$s)
                GROUP BY r.reviewee_user_id
            ) r ON r.member_id = m.id
            WHERE %1$s
            ORDER BY m.id
            """;

    private static final String STATS_OF_IDS = STATS.formatted("m.id IN (:ids)", COUNTED_REVIEWS);

    private static final String STATS_OF_RECOMMENDABLE = STATS.formatted(RECOMMENDABLE, COUNTED_REVIEWS);

    private static final RowMapper<InfluencerProfile> PROFILE = (rs, row) -> {
        Array array = rs.getArray("categories");
        List<String> categories = array == null ? List.of() : List.of((String[]) array.getArray());
        return new InfluencerProfile(
                rs.getLong("id"),
                Role.valueOf(rs.getString("role")),
                UserStatus.valueOf(rs.getString("status")),
                categories,
                rs.getString("bio"),
                rs.getBoolean("masked"));
    };

    private static final RowMapper<InfluencerPopularity.Stats> STATS_ROW = (rs, row) -> new InfluencerPopularity.Stats(
            rs.getLong("id"),
            rs.getLong("completed"),
            rs.getLong("reviews"),
            rs.getLong("rating_sum"));

    private final NamedParameterJdbcTemplate jdbc;

    /** 회원 사본의 프로필. 사본이 없는 ID는 빠진다 */
    public Map<Long, InfluencerProfile> profiles(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return byId(jdbc.query(PROFILES, new MapSqlParameterSource("ids", ids), PROFILE));
    }

    /** 이 ID보다 큰 회원 사본의 프로필을 ID 오름차순으로 size개(정합 점검이 모든 회원을 훑는다) */
    public List<InfluencerProfile> profilesAfter(long afterId, int size) {
        return jdbc.query(PROFILES_AFTER, new MapSqlParameterSource("afterId", afterId).addValue("size", size), PROFILE);
    }

    /** 주어진 회원 중 추천 가능한 회원의 프로필(회원 ID → 프로필) */
    public Map<Long, InfluencerProfile> recommendableProfiles(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return byId(jdbc.query(RECOMMENDABLE_PROFILES, params().addValue("ids", ids), PROFILE));
    }

    /** 이 회원과 출연을 함께 마친 상대(원본 없음 제외, 본인 제외) */
    public Set<Long> completedPartners(long userId) {
        return new LinkedHashSet<>(jdbc.queryForList(COMPLETED_PARTNERS, params().addValue("userId", userId), Long.class));
    }

    /** 주어진 회원들의 인기 재료. 출연·리뷰가 없는 회원도 0으로 들어간다(주어진 순서) */
    public List<InfluencerPopularity.Stats> popularityStats(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, InfluencerPopularity.Stats> found = new HashMap<>();
        for (InfluencerPopularity.Stats one : jdbc.query(STATS_OF_IDS, params().addValue("ids", ids), STATS_ROW)) {
            found.put(one.memberId(), one);
        }
        List<InfluencerPopularity.Stats> stats = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            stats.add(found.getOrDefault(id, new InfluencerPopularity.Stats(id, 0, 0, 0)));
        }
        return stats;
    }

    /**
     * 추천 가능한 모든 회원의 인기 재료(인기순 채우기). 회원 ID를 넘기지 않고 SQL 조건으로 고른다(수천 명이어도 바인드 수가 늘지 않는다).
     * 회원 ID 오름차순.
     */
    public List<InfluencerPopularity.Stats> popularityStatsOfRecommendable() {
        return jdbc.query(STATS_OF_RECOMMENDABLE, params(), STATS_ROW);
    }

    /** 모든 받은 출연 리뷰의 평균 별점(리뷰가 없으면 0) */
    public double overallMeanRating() {
        Double mean = jdbc.queryForObject(OVERALL_MEAN_RATING, new MapSqlParameterSource(), Double.class);
        return mean == null ? 0 : mean;
    }

    // SQL의 상태 값. 쓰지 않는 문장에 넘겨도 무시된다
    private static MapSqlParameterSource params() {
        List<String> influencerRoles = new ArrayList<>();
        for (Role role : InfluencerProfile.INFLUENCER_ROLES) {
            influencerRoles.add(role.name());
        }
        return new MapSqlParameterSource("influencerRoles", influencerRoles)
                .addValue("active", UserStatus.ACTIVE.name())
                .addValue("completed", AppearanceResult.COMPLETED.name());
    }

    private static Map<Long, InfluencerProfile> byId(List<InfluencerProfile> profiles) {
        Map<Long, InfluencerProfile> byId = new HashMap<>();
        for (InfluencerProfile profile : profiles) {
            byId.put(profile.id(), profile);
        }
        return byId;
    }
}
