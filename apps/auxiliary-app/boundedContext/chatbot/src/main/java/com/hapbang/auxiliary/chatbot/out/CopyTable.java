package com.hapbang.auxiliary.chatbot.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.hapbang.auxiliary.chatbot.domain.CopyOutcome;
import com.hapbang.auxiliary.chatbot.domain.CopyWrite;

/**
 * 사본 테이블 하나의 반영 SQL (#717). 출연({@link AppearanceCopyStore})·회원·강의 사본이 같은 규칙을 함께 쓴다.
 * <ul>
 *     <li>반영은 upsert 한 문장이다. 값은 받은 version이 크거나(같은데 해시가 다르면) 바뀐다. version이 비어 있을 수 있는 회원은
 *     {@link CopyOutcome}의 규칙(받은 것이 비고 우리에 있으면 무시, 우리가 비고 받은 것에 있으면 반영)을 SQL에도 같게 둔다.</li>
 *     <li>이벤트는 값을 바꿀 때만 행을 갱신한다. 싱크는 값이 같아도 원본에 있다는 표시(원본 없음 해제·본 회차·받은 시각)를 남긴다
 *     ({@code tracksSource}인 테이블만. 회원은 원본 없음을 표시하지 않으므로 값을 바꿀 때만 갱신한다).</li>
 *     <li>반영 전 값은 PostgreSQL 18 {@code RETURNING old.*}로 읽고, 행을 건드리지 않았으면 우리 행을 따로 읽는다(결과는 로그·집계용).
 *     원본 없음 표시를 풀었는지도 함께 돌려준다(색인 요청용, #718).</li>
 * </ul>
 * 파라미터 이름은 컬럼 이름과 같고, 키·데이터 컬럼 외에 {@code :version}, {@code :hash}, {@code :runId}, {@code :now}를 받는다.
 */
final class CopyTable {

    // 받은 값으로 바꾸는 조건
    private static final String APPLY = "((excluded.version IS NOT NULL AND (c.version IS NULL "
            + "OR excluded.version > c.version "
            + "OR (excluded.version = c.version AND excluded.dto_hash <> c.dto_hash))) "
            + "OR (excluded.version IS NULL AND c.version IS NULL AND excluded.dto_hash <> c.dto_hash))";

    private final String upsert;
    private final String current;
    private final String markMissing;

    /**
     * @param table        스키마 없는 테이블 이름
     * @param key          키 컬럼
     * @param columns      데이터 컬럼(키·version·해시·표시 컬럼 제외)
     * @param jsonColumns  jsonb로 넣을 데이터 컬럼(파라미터는 JSON 문자열)
     * @param tracksSource 원본 없음 표시 컬럼({@code missing_in_source}, {@code seen_run_id})이 있는지
     */
    CopyTable(String table, String key, List<String> columns, Set<String> jsonColumns, boolean tracksSource) {
        String qualified = "chatbot." + table;
        String insertColumns = String.join(", ", columns) + ", version, dto_hash"
                + (tracksSource ? ", missing_in_source, seen_run_id" : "") + ", synced_at";
        String values = columns.stream()
                .map(column -> jsonColumns.contains(column) ? "CAST(:" + column + " AS jsonb)" : ":" + column)
                .collect(Collectors.joining(", "))
                + ", :version, :hash" + (tracksSource ? ", FALSE, :runId" : "") + ", :now";
        String updates = columns.stream()
                .map(column -> column + " = CASE WHEN " + APPLY + " THEN excluded." + column + " ELSE c." + column + " END")
                .collect(Collectors.joining(",\n    "))
                + ",\n    version = CASE WHEN " + APPLY + " THEN excluded.version ELSE c.version END"
                + ",\n    dto_hash = CASE WHEN " + APPLY + " THEN excluded.dto_hash ELSE c.dto_hash END"
                + (tracksSource ? ",\n    missing_in_source = FALSE,\n    seen_run_id = COALESCE(excluded.seen_run_id, c.seen_run_id)" : "")
                + ",\n    synced_at = excluded.synced_at";
        String returning = "old.version AS old_version, old.dto_hash AS old_hash"
                + (tracksSource ? ", COALESCE(old.missing_in_source, FALSE) AS old_missing" : ", FALSE AS old_missing");
        this.upsert = "INSERT INTO " + qualified + " AS c (" + key + ", " + insertColumns + ")\n"
                + "VALUES (:" + key + ", " + values + ")\n"
                + "ON CONFLICT (" + key + ") DO UPDATE SET\n    " + updates + "\n"
                + "WHERE " + APPLY + (tracksSource ? " OR excluded.seen_run_id IS NOT NULL" : "") + "\n"
                + "RETURNING " + returning;
        this.current = "SELECT version AS old_version, dto_hash AS old_hash, "
                + "FALSE AS old_missing FROM " + qualified + " WHERE " + key + " = :" + key;
        // 이번 회차에서 보지 못했고 회차 시작 전부터 받지 않은 행. 스캔 중·뒤에 이벤트로 들어온 행은 synced_at이 늦어 빠진다
        this.markMissing = tracksSource
                ? "UPDATE " + qualified + " SET missing_in_source = TRUE\n"
                + "WHERE missing_in_source = FALSE AND (seen_run_id IS NULL OR seen_run_id <> :runId) AND synced_at < :startedAt\n"
                + "RETURNING " + key + " AS id, version AS version"
                : null;
    }

    /**
     * 받은 값을 반영한다.
     *
     * @param params          키·데이터 컬럼 값
     * @param incomingVersion 받은 version. 회원은 비어 있을 수 있다
     * @param runId           싱크 회차 ID({@link com.hapbang.auxiliary.chatbot.domain.ApplySource#runId()}). 이벤트와 회원이면 null
     */
    CopyWrite save(NamedParameterJdbcTemplate jdbc, MapSqlParameterSource params, Long incomingVersion, String hash,
            Long runId, LocalDateTime now) {
        params.addValue("version", incomingVersion).addValue("hash", hash).addValue("runId", runId).addValue("now", now);
        RowMapper<CopyWrite> outcome = (rs, row) -> {
            Long ourVersion = rs.getObject("old_version", Long.class);
            String ourHash = rs.getString("old_hash");
            return new CopyWrite(CopyOutcome.decide(ourVersion, ourHash, incomingVersion, hash),
                    ourVersion, rs.getBoolean("old_missing"));
        };
        List<CopyWrite> written = jdbc.query(upsert, params, outcome);
        return written.isEmpty() ? jdbc.queryForObject(current, params, outcome) : written.getFirst();
    }

    /** 끝까지 성공한 회차의 끝에 원본에 없던 행을 표시한다. 새로 표시한 행을 돌려준다 */
    List<MissingCopy> markMissing(NamedParameterJdbcTemplate jdbc, long runId, LocalDateTime startedAt) {
        if (markMissing == null) {
            return List.of();
        }
        return jdbc.query(markMissing, new MapSqlParameterSource().addValue("runId", runId).addValue("startedAt", startedAt),
                (rs, row) -> new MissingCopy(rs.getLong("id"), rs.getObject("version", Long.class)));
    }
}
