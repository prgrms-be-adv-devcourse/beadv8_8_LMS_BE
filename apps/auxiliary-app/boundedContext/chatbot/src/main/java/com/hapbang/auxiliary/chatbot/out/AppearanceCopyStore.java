package com.hapbang.auxiliary.chatbot.out;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.hapbang.auxiliary.chatbot.domain.AppearanceCopy;
import com.hapbang.auxiliary.chatbot.domain.CopyOutcome;

import lombok.RequiredArgsConstructor;

/**
 * 출연 사본 {@code chatbot.appearance_copy} (#716).
 * <p>
 * 반영은 upsert 한 문장이다. 이벤트와 싱크가 같은 행을 동시에 바꿔도, 값은 받은 version이 크거나(같은데 해시가 다르면) 바뀌고
 * 아니면 그대로라 큰 version을 잃지 않는다.
 * <ul>
 *     <li>이벤트: 값을 바꾸지 않을 때(우리가 새것·같음)는 행을 건드리지 않는다. 늦게 온 옛 이벤트가 받은 시각을 고치거나
 *     원본 없음 표시를 풀지 않는다.</li>
 *     <li>싱크: 값을 바꾸지 않아도 원본에 있다는 표시(원본 없음 해제, 본 회차, 받은 시각)는 남긴다. 남기지 않으면 회차 끝의
 *     원본 없음 표시가 멀쩡한 행을 잡는다.</li>
 * </ul>
 * 반영 전 값은 PostgreSQL 18 {@code RETURNING old.*}로 같은 문장에서 읽어 결과({@link CopyOutcome})를 정한다. 이벤트를 건드리지
 * 않아 돌려받은 행이 없으면 그때 우리 행을 읽어 정한다. 이 읽기는 다른 문장이라 그 사이 다른 반영이 끼면 결과가 어긋날 수 있다.
 * 결과는 이벤트 로그에만 쓰고(싱크는 늘 행을 돌려받는다) 사본 값은 upsert가 지키므로 받아들인다.
 */
@Repository
@RequiredArgsConstructor
public class AppearanceCopyStore {

    // 받은 값으로 바꾸는 조건: version이 크거나, 같은데 값이 다르다
    private static final String APPLY = "(excluded.version > c.version "
            + "OR (excluded.version = c.version AND excluded.dto_hash <> c.dto_hash))";

    private static final String UPSERT = """
            INSERT INTO chatbot.appearance_copy AS c
                (id, host_id, guest_id, result, scheduled_at, version, dto_hash, missing_in_source, seen_run_id, synced_at)
            VALUES (:id, :hostId, :guestId, :result, :scheduledAt, :version, :hash, FALSE, :runId, :now)
            ON CONFLICT (id) DO UPDATE SET
                host_id = CASE WHEN %1$s THEN excluded.host_id ELSE c.host_id END,
                guest_id = CASE WHEN %1$s THEN excluded.guest_id ELSE c.guest_id END,
                result = CASE WHEN %1$s THEN excluded.result ELSE c.result END,
                scheduled_at = CASE WHEN %1$s THEN excluded.scheduled_at ELSE c.scheduled_at END,
                dto_hash = CASE WHEN %1$s THEN excluded.dto_hash ELSE c.dto_hash END,
                version = CASE WHEN %1$s THEN excluded.version ELSE c.version END,
                missing_in_source = FALSE,
                seen_run_id = COALESCE(excluded.seen_run_id, c.seen_run_id),
                synced_at = excluded.synced_at
            WHERE %1$s OR excluded.seen_run_id IS NOT NULL
            RETURNING old.version AS old_version, old.dto_hash AS old_hash
            """.formatted(APPLY);

    private static final String CURRENT = "SELECT version AS old_version, dto_hash AS old_hash "
            + "FROM chatbot.appearance_copy WHERE id = :id";

    // 이번 회차에서 보지 못했고 회차 시작 전부터 받지 않은 행. 스캔 중·뒤에 이벤트로 들어온 행은 synced_at이 늦어 빠진다
    private static final String MARK_MISSING = """
            UPDATE chatbot.appearance_copy SET missing_in_source = TRUE
            WHERE missing_in_source = FALSE
              AND (seen_run_id IS NULL OR seen_run_id <> :runId)
              AND synced_at < :startedAt
            RETURNING id, version
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 받은 값을 반영한다.
     *
     * @param runId 싱크 회차 ID. 이벤트면 null
     */
    public CopyWrite save(AppearanceCopy copy, Long runId, LocalDateTime now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", copy.id())
                .addValue("hostId", copy.hostId())
                .addValue("guestId", copy.guestId())
                .addValue("result", copy.result().name())
                .addValue("scheduledAt", copy.scheduledAt())
                .addValue("version", copy.version())
                .addValue("hash", copy.hash())
                .addValue("runId", runId)
                .addValue("now", now);
        RowMapper<CopyWrite> outcome = (rs, row) -> {
            Long ourVersion = rs.getObject("old_version", Long.class);
            String ourHash = rs.getString("old_hash");
            return new CopyWrite(CopyOutcome.decide(ourVersion, ourHash, copy.version(), copy.hash()), ourVersion);
        };
        List<CopyWrite> written = jdbc.query(UPSERT, params, outcome);
        return written.isEmpty() ? jdbc.queryForObject(CURRENT, params, outcome) : written.getFirst();
    }

    /** 끝까지 성공한 회차의 끝에 원본에 없던 행을 표시한다. 새로 표시한 행을 돌려준다 */
    public List<MissingCopy> markMissing(long runId, LocalDateTime startedAt) {
        return jdbc.query(MARK_MISSING, new MapSqlParameterSource().addValue("runId", runId).addValue("startedAt", startedAt),
                (rs, row) -> new MissingCopy(rs.getLong("id"), rs.getLong("version")));
    }
}
