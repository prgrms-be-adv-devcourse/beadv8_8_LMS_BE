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
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

import lombok.RequiredArgsConstructor;

/**
 * 인플루언서 추천이 읽는 사본 (#719). 쓰지 않는다. 트랜잭션은 열지 않는다(문장 하나씩, 추천 사이에 ES 호출이 있다).
 * <p>
 * <b>추천 가능 = 역할 {@code INFLUENCER}·{@code VERIFIED_INFLUENCER} ∧ 상태 {@code ACTIVE} ∧ 마스킹 안 됨.</b>
 * 색인 문서의 {@code hidden}과 같은 규칙이다({@link Profile#recommendable()}와 SQL {@link #RECOMMENDABLE}).
 * 정지를 빼는 것은 추천 후보 조건이다({@code MemberVisibility}는 탈퇴만 숨긴다).
 */
@Repository
@RequiredArgsConstructor
public class InfluencerRecommendStore {

    // 추천 가능 조건(Profile.recommendable과 같다). 별칭 m은 member_copy
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
            WHERE a.result = 'COMPLETED' AND NOT a.missing_in_source
              AND (a.host_id = :userId OR a.guest_id = :userId)
            """;

    // 완료 출연 수. 호스트·게스트 모두 센다
    private static final String COMPLETED_COUNTS = """
            SELECT p.member_id, count(*) AS completed
            FROM chatbot.appearance_copy a
            CROSS JOIN LATERAL (VALUES (a.host_id), (a.guest_id)) AS p(member_id)
            WHERE a.result = 'COMPLETED' AND NOT a.missing_in_source
              AND p.member_id IN (:ids)
            GROUP BY p.member_id
            """;

    // 받은 출연 리뷰. 리뷰나 그 출연이 원본 없음이면 뺀다. 출연 사본이 아직 없는 리뷰는 센다(사본 사이에 순서 보장이 없다)
    private static final String REVIEWS = """
            FROM chatbot.appearance_review_copy r
            LEFT JOIN chatbot.appearance_copy a ON a.id = r.appearance_id
            WHERE NOT r.missing_in_source AND (a.id IS NULL OR NOT a.missing_in_source)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** 회원 사본의 프로필. 사본이 없는 ID는 빠진다 */
    public Map<Long, Profile> profiles(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Profile> profiles = new HashMap<>();
        jdbc.query(PROFILES, new MapSqlParameterSource("ids", ids), rs -> {
            Profile profile = toProfile(rs);
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
                "SELECT m.id FROM chatbot.member_copy m WHERE m.id IN (:ids) AND " + RECOMMENDABLE,
                new MapSqlParameterSource("ids", ids), Long.class));
    }

    /** 추천 가능한 모든 회원(인기순 후보). 수천 명 규모라 한 번에 읽는다 */
    public List<Long> allRecommendable() {
        return jdbc.queryForList("SELECT m.id FROM chatbot.member_copy m WHERE " + RECOMMENDABLE + " ORDER BY m.id",
                new MapSqlParameterSource(), Long.class);
    }

    /** 이 회원과 출연을 함께 마친 상대(원본 없음 제외, 본인 제외) */
    public Set<Long> completedPartners(long userId) {
        Set<Long> partners = new LinkedHashSet<>(jdbc.queryForList(COMPLETED_PARTNERS,
                new MapSqlParameterSource("userId", userId), Long.class));
        partners.remove(userId);
        return partners;
    }

    /** 회원들의 인기 재료. 출연·리뷰가 없는 회원도 0으로 들어간다(주어진 순서) */
    public List<InfluencerPopularity.Stats> popularityStats(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
        Map<Long, Long> completed = new HashMap<>();
        jdbc.query(COMPLETED_COUNTS, params, rs -> {
            completed.put(rs.getLong("member_id"), rs.getLong("completed"));
        });
        Map<Long, ReviewSum> reviews = new HashMap<>();
        jdbc.query("SELECT r.reviewee_user_id AS member_id, count(*) AS reviews, sum(r.rating) AS rating_sum " + REVIEWS
                + " AND r.reviewee_user_id IN (:ids) GROUP BY r.reviewee_user_id", params, rs -> {
                    reviews.put(rs.getLong("member_id"), new ReviewSum(rs.getLong("reviews"), rs.getLong("rating_sum")));
                });
        List<InfluencerPopularity.Stats> stats = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            ReviewSum review = reviews.getOrDefault(id, ReviewSum.NONE);
            stats.add(new InfluencerPopularity.Stats(id, completed.getOrDefault(id, 0L), review.count(), review.sum()));
        }
        return stats;
    }

    /** 모든 받은 출연 리뷰의 평균 별점(리뷰가 없으면 0) */
    public double overallMeanRating() {
        Double mean = jdbc.queryForObject("SELECT coalesce(avg(r.rating), 0) " + REVIEWS, new MapSqlParameterSource(),
                Double.class);
        return mean == null ? 0 : mean;
    }

    private static Profile toProfile(ResultSet rs) throws SQLException {
        Array array = rs.getArray("categories");
        List<String> categories = array == null ? List.of() : List.of((String[]) array.getArray());
        return new Profile(rs.getLong("id"), Role.valueOf(rs.getString("role")), UserStatus.valueOf(rs.getString("status")),
                categories, rs.getString("bio"), rs.getBoolean("masked"));
    }

    private record ReviewSum(long count, long sum) {

        static final ReviewSum NONE = new ReviewSum(0, 0);
    }

    /**
     * 회원 사본 한 명의 프로필.
     *
     * @param categories 분야 코드(회원이 고른 순서)
     * @param bio        자기소개(없으면 null). 임베딩 API로만 보낸다(LLM 프롬프트·로그에 넣지 않는다)
     * @param masked     개인정보를 마스킹한 회원
     */
    public record Profile(
            long id,
            Role role,
            UserStatus status,
            List<String> categories,
            String bio,
            boolean masked
    ) {

        /** 추천 가능(색인 문서 {@code hidden=false})한지. SQL {@code RECOMMENDABLE}과 같은 규칙이다 */
        public boolean recommendable() {
            return !masked && status == UserStatus.ACTIVE
                    && (role == Role.INFLUENCER || role == Role.VERIFIED_INFLUENCER);
        }
    }
}
