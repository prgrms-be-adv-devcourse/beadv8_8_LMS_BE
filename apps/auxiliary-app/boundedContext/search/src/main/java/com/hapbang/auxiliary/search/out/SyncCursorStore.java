package com.hapbang.auxiliary.search.out;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 증분 보정 커서 {@code search.sync_cursor}(#772). 한 문장씩 바로 커밋한다(페이지마다 저장해 회차 중간에 멈춰도 거기서 이어 가게).
 * <p>
 * JPA Repository가 아니라 JDBC다. 처음에는 Spring Data Repository 빈에 검색 기능 스위치를 붙일 수 없어 JDBC로 썼다. 스위치를
 * 지운 뒤(#860)에도 행 하나를 SQL 한 문장으로 다루기에 충분해 그대로 둔다.
 */
@Repository
@RequiredArgsConstructor
public class SyncCursorStore {

    private static final String FIND = """
            SELECT name, member_updated_at, member_id, caught_up_at FROM search.sync_cursor WHERE name = :name
            """;

    // 커서만 옮긴다. 끝까지 읽은 시각은 그대로 둔다
    private static final String MOVE = """
            INSERT INTO search.sync_cursor (name, member_updated_at, member_id)
            VALUES (:name, :memberUpdatedAt, :memberId)
            ON CONFLICT (name) DO UPDATE SET
                member_updated_at = excluded.member_updated_at,
                member_id = excluded.member_id
            """;

    private static final String CAUGHT_UP = """
            UPDATE search.sync_cursor SET caught_up_at = :at WHERE name = :name
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<SyncCursor> find(String name) {
        return jdbc.query(FIND, new MapSqlParameterSource("name", name), (rs, rowNum) -> new SyncCursor(
                rs.getString("name"),
                rs.getObject("member_updated_at", LocalDateTime.class),
                rs.getLong("member_id"),
                rs.getObject("caught_up_at", LocalDateTime.class))).stream().findFirst();
    }

    /**
     * 커서를 옮긴다. 없으면 만든다. 페이지를 반영한 뒤(회원 앱이 준 다음 커서), 처음 실행(지금 시각, 회원 ID 0), 바깥에서 시작 시각을 정할 때(#773)
     * 쓴다. 앞으로만 가지 않는다: 겹쳐 읽은 첫 페이지나 #773 되돌리기는 커서를 앞으로 당길 수 있고, 다시 읽어도 version으로 걸러져 안전하다.
     */
    public void moveTo(String name, LocalDateTime memberUpdatedAt, long memberId) {
        jdbc.update(MOVE, new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("memberUpdatedAt", memberUpdatedAt)
                .addValue("memberId", memberId));
    }

    /** 끝(hasNext=false)까지 읽은 시각을 남긴다. 커서가 없으면 아무것도 하지 않는다 */
    public void caughtUp(String name, LocalDateTime at) {
        jdbc.update(CAUGHT_UP, new MapSqlParameterSource().addValue("name", name).addValue("at", at));
    }
}
