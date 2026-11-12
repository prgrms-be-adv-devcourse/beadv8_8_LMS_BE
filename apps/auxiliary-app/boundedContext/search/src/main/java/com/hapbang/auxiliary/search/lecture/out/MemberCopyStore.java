package com.hapbang.auxiliary.search.lecture.out;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;

/**
 * 회원 사본 {@code search.member_copy}(#882 Q1). 강의 문서의 판매자 닉네임·상태 출처다. 누가 판매자가 될지 몰라 모든 회원을 둔다.
 * <p>
 * <b>회원 version 비교</b>({@link UserDto} 규칙): 들어온 version이 더 클 때만 반영한다. version이 없는 정보는 사본에도 version이 없을 때만
 * 반영한다(버전 도입 전 옛 이벤트). 순서가 뒤바뀐 이벤트·같은 이벤트 두 번은 아무것도 바꾸지 않는다.
 * <p>
 * JDBC다(검색 모듈의 다른 표 {@code SyncCursorStore}와 같다). 행 하나를 SQL 몇 문장으로 다루기에 충분하다.
 */
@Repository
@RequiredArgsConstructor
public class MemberCopyStore {

    private static final String FIND = """
            SELECT id, nickname, status FROM search.member_copy WHERE id = :id
            """;

    private static final String INSERT_IF_ABSENT = """
            INSERT INTO search.member_copy (id, nickname, status, version, synced_at)
            VALUES (:id, :nickname, :status, :version, :now)
            ON CONFLICT (id) DO NOTHING
            """;

    // 비교와 덮기 사이에 다른 반영(이벤트와 보정)이 끼지 않게 행을 잠근다
    private static final String LOCK = """
            SELECT nickname, status, version FROM search.member_copy WHERE id = :id FOR UPDATE
            """;

    private static final String UPDATE = """
            UPDATE search.member_copy SET nickname = :nickname, status = :status, version = :version, synced_at = :now
            WHERE id = :id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** 판매자 정보. 회원 사본에 없으면(그 회원 이벤트를 아직 받지 못함) 비어 있다 */
    public Optional<Seller> findSeller(long userId) {
        return jdbc.query(FIND, new MapSqlParameterSource("id", userId), (rs, rowNum) -> new Seller(
                rs.getLong("id"),
                rs.getString("nickname"),
                isActive(rs.getString("status")))).stream().findFirst();
    }

    /**
     * 회원 정보를 사본에 반영한다.
     *
     * @return 강의 문서에 넣는 판매자 정보(닉네임·상태)가 바뀌었으면(처음 넣은 회원 포함) 바뀐 뒤의 판매자 정보. 옛 version이거나 닉네임·상태가 같으면
     * 비어 있다(강의 문서를 고칠 일이 없다)
     * @throws NullPointerException 회원 ID·상태가 없는 경우
     */
    @Transactional
    public Optional<Seller> apply(UserDto user, LocalDateTime now) {
        Objects.requireNonNull(user.id(), "회원 ID는 필수입니다.");
        Objects.requireNonNull(user.status(), "회원 상태는 필수입니다.");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", user.id())
                .addValue("nickname", user.nickname())
                .addValue("status", user.status().name())
                .addValue("version", user.version())
                .addValue("now", now);
        Seller incoming = new Seller(user.id(), user.nickname(), user.status() == UserStatus.ACTIVE);
        if (jdbc.update(INSERT_IF_ABSENT, params) == 1) {
            return Optional.of(incoming);
        }
        Stored stored = jdbc.queryForObject(LOCK, params, (rs, rowNum) -> new Stored(
                rs.getString("nickname"),
                rs.getString("status"),
                rs.getObject("version", Long.class)));
        if (!isNewer(user.version(), stored.version())) {
            return Optional.empty();
        }
        jdbc.update(UPDATE, params);
        boolean changed = !Objects.equals(stored.nickname(), user.nickname())
                || !stored.status().equals(user.status().name());
        return changed ? Optional.of(incoming) : Optional.empty();
    }

    /** {@link UserDto} 규칙: version이 있으면 더 클 때만, 없으면 사본에도 version이 없을 때만 반영한다 */
    private static boolean isNewer(Long incoming, Long stored) {
        if (incoming == null) {
            return stored == null;
        }
        return stored == null || stored < incoming;
    }

    private static boolean isActive(String status) {
        return UserStatus.ACTIVE.name().equals(status);
    }

    private record Stored(String nickname, String status, Long version) {
    }
}
