package com.hapbang.auxiliary.chatbot.out;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;

import lombok.RequiredArgsConstructor;

/**
 * ES 색인 요청 {@code chatbot.index_task} (#717, #718). 데이터 변경과 같은 트랜잭션에 적고, 커밋 뒤 실행기가 가져가 실행·재시도한다.
 * <ul>
 *     <li>적기: 트랜잭션은 부르는 쪽이 연다.</li>
 *     <li>가져가기({@link #claim}): 짧은 트랜잭션 하나에서 {@code FOR UPDATE SKIP LOCKED}로 고르고 다음 시도 시각을 임대 끝으로 미룬다.
 *     ES 호출은 트랜잭션 밖에서 하므로 DB 연결을 붙잡지 않고, 다른 pod는 임대가 끝나기 전에 같은 요청을 가져가지 않는다.
 *     실행 중 pod가 죽으면 임대가 끝난 뒤 다른 pod가 다시 가져간다.</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class IndexTaskStore {

    private static final int ERROR_MAX_LENGTH = 500;

    private final NamedParameterJdbcTemplate jdbc;

    /** 색인 요청을 적는다. 바로 실행할 수 있게 다음 시도 시각을 지금으로 둔다 */
    public void add(IndexType type, String docId, IndexAction action, LocalDateTime now) {
        addAll(type, List.of(docId), action, now);
    }

    /** 여러 문서의 색인 요청을 한 번에 적는다 */
    public void addAll(IndexType type, Collection<String> docIds, IndexAction action, LocalDateTime now) {
        if (docIds.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = docIds.stream()
                .map(docId -> new MapSqlParameterSource().addValue("indexType", type.name()).addValue("docId", docId)
                        .addValue("action", action.name()).addValue("now", now))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO chatbot.index_task (index_type, doc_id, action, next_attempt_at, created_at)
                VALUES (:indexType, :docId, :action, :now, :now)
                """, batch);
    }

    /**
     * 실행할 요청을 가져간다. 시도 횟수를 하나 올리고 다음 시도 시각을 {@code leaseUntil}로 미룬다.
     *
     * @param types 가져갈 종류(처리기가 있는 종류만). 비어 있으면 빈 목록
     * @return 오래된 순
     */
    public List<IndexTask> claim(Collection<IndexType> types, int limit, LocalDateTime now, LocalDateTime leaseUntil) {
        if (types.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                WITH picked AS (
                    SELECT id FROM chatbot.index_task
                    WHERE done_at IS NULL AND next_attempt_at <= :now AND index_type IN (:types)
                    ORDER BY next_attempt_at, id
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE chatbot.index_task t SET attempts = t.attempts + 1, next_attempt_at = :leaseUntil
                FROM picked WHERE t.id = picked.id
                RETURNING t.id, t.index_type, t.doc_id, t.action, t.attempts
                """, new MapSqlParameterSource()
                        .addValue("types", types.stream().map(Enum::name).toList())
                        .addValue("limit", limit)
                        .addValue("now", now)
                        .addValue("leaseUntil", leaseUntil),
                (rs, row) -> new IndexTask(rs.getLong("id"), IndexType.valueOf(rs.getString("index_type")),
                        rs.getString("doc_id"), IndexAction.valueOf(rs.getString("action")), rs.getInt("attempts")))
                .stream()
                .sorted(Comparator.comparingLong(IndexTask::id))
                .toList();
    }

    /** 요청을 끝낸다 */
    public void done(Collection<Long> ids, LocalDateTime now) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update("UPDATE chatbot.index_task SET done_at = :now, last_error = NULL WHERE id IN (:ids)",
                new MapSqlParameterSource().addValue("ids", ids).addValue("now", now));
    }

    /** 실패한 요청을 다음 시도 시각으로 미룬다 */
    public void retryLater(Collection<Long> ids, LocalDateTime nextAttemptAt, String error) {
        if (ids.isEmpty()) {
            return;
        }
        String trimmed = error.length() > ERROR_MAX_LENGTH ? error.substring(0, ERROR_MAX_LENGTH) : error;
        jdbc.update("UPDATE chatbot.index_task SET next_attempt_at = :next, last_error = :error WHERE id IN (:ids)",
                new MapSqlParameterSource().addValue("ids", ids).addValue("next", nextAttemptAt).addValue("error", trimmed));
    }

    /** 이 시각 전에 끝난 요청을 지운다. @return 지운 행 수 */
    public int deleteDoneBefore(LocalDateTime before) {
        return jdbc.update("DELETE FROM chatbot.index_task WHERE done_at < :before",
                new MapSqlParameterSource().addValue("before", before));
    }

    /**
     * 가져간 요청 하나.
     *
     * @param attempts 이번 시도를 넣은 시도 횟수
     */
    public record IndexTask(long id, IndexType type, String docId, IndexAction action, int attempts) {
    }
}
