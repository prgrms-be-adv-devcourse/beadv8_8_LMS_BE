package com.hapbang.auxiliary.search.out;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;

import lombok.RequiredArgsConstructor;

/**
 * 문서 수 정합성 점검 결과 {@code search.consistency_check}(#772, PR #833 리뷰 Q2). 점검은 한 pod에서만 돌지만, 지표는 모든 pod가 이 행을 읽어
 * 같은 값을 낸다(pod 메모리에 두면 예전에 점검한 pod가 낡은 차이를 계속 낸다). JDBC인 이유는 {@link SyncCursorStore}와 같다.
 */
@Repository
@ConditionalOnSearchEnabled
@RequiredArgsConstructor
public class ConsistencyCheckStore {

    private static final String FIND = """
            SELECT target, source_count, index_count, checked_at FROM search.consistency_check WHERE target = :target
            """;

    private static final String SAVE = """
            INSERT INTO search.consistency_check (target, source_count, index_count, checked_at)
            VALUES (:target, :sourceCount, :indexCount, :checkedAt)
            ON CONFLICT (target) DO UPDATE SET
                source_count = excluded.source_count,
                index_count = excluded.index_count,
                checked_at = excluded.checked_at
            """;

    private static final String CLEAR = "DELETE FROM search.consistency_check WHERE target = :target";

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<ConsistencyCheck> find(String target) {
        return jdbc.query(FIND, new MapSqlParameterSource("target", target), (rs, rowNum) -> new ConsistencyCheck(
                rs.getString("target"),
                rs.getLong("source_count"),
                rs.getLong("index_count"),
                rs.getObject("checked_at", LocalDateTime.class))).stream().findFirst();
    }

    /** 이번 점검 결과로 바꾼다. 없으면 만든다 */
    public void save(ConsistencyCheck check) {
        jdbc.update(SAVE, new MapSqlParameterSource()
                .addValue("target", check.target())
                .addValue("sourceCount", check.sourceCount())
                .addValue("indexCount", check.indexCount())
                .addValue("checkedAt", check.checkedAt()));
    }

    /** 이번 회차에 점검하지 못했다. 낡은 결과를 지운다 */
    public void clear(String target) {
        jdbc.update(CLEAR, new MapSqlParameterSource("target", target));
    }
}
