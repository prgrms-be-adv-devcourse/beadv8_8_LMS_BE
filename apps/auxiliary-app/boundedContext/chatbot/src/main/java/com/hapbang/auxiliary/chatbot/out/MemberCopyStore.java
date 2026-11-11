package com.hapbang.auxiliary.chatbot.out;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.hapbang.auxiliary.chatbot.domain.MemberCopy;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

import lombok.RequiredArgsConstructor;

/**
 * 회원 사본 {@code chatbot.member_copy} (#717). 반영 규칙은 {@link CopyTable}. 회원은 원본 없음을 표시하지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class MemberCopyStore {

    static final CopyTable TABLE = new CopyTable("member_copy", "id", List.of("role", "status", "categories", "bio"),
            Set.of("categories"), true, false);

    // 보임 = 사본 있음 ∧ 탈퇴 아님 ∧ 마스킹 안 됨 (MemberVisibility). 회원 사본의 별칭은 m
    private static final String VISIBLE_MEMBER =
            "m.status <> 'WITHDRAWN' AND NOT EXISTS (SELECT 1 FROM chatbot.masked_member x WHERE x.user_id = m.id)";

    private static final String VISIBLE = "SELECT m.id FROM chatbot.member_copy m WHERE m.id IN (:ids) AND " + VISIBLE_MEMBER;

    private static final String ROLE_AND_STATUS = """
            SELECT m.role, m.status FROM chatbot.member_copy m
            WHERE m.id = :id AND NOT EXISTS (SELECT 1 FROM chatbot.masked_member x WHERE x.user_id = m.id)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public CopyWrite save(MemberCopy copy, LocalDateTime now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", copy.id())
                .addValue("role", copy.role().name())
                .addValue("status", copy.status().name())
                .addValue("categories", JsonColumns.write(copy.categories()))
                .addValue("bio", copy.bio());
        return TABLE.save(jdbc, params, copy.version(), copy.hash(), null, now);
    }

    /**
     * SQL 조건: {@code userIdColumn}의 회원이 보인다({@link #findVisible}과 같은 규칙). 다른 store가 목록을 SQL 안에서 거르고 나서
     * {@code LIMIT}을 걸어야 할 때 쓴다.
     */
    static String visibleCondition(String userIdColumn) {
        return "EXISTS (SELECT 1 FROM chatbot.member_copy m WHERE m.id = " + userIdColumn + " AND " + VISIBLE_MEMBER + ")";
    }

    /** 주어진 회원 중 보이는 회원의 ID */
    public Set<Long> findVisible(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.queryForList(VISIBLE, new MapSqlParameterSource("ids", userIds), Long.class));
    }

    /** 마스킹하지 않은 회원의 역할·상태. 사본이 없거나 마스킹했으면 빈 값 */
    public Optional<RoleAndStatus> findRoleAndStatus(long userId) {
        return jdbc.query(ROLE_AND_STATUS, new MapSqlParameterSource("id", userId),
                (rs, row) -> new RoleAndStatus(Role.valueOf(rs.getString("role")),
                        UserStatus.valueOf(rs.getString("status")))).stream().findFirst();
    }

    /** 회원 사본의 역할·상태 */
    public record RoleAndStatus(Role role, UserStatus status) {
    }
}
