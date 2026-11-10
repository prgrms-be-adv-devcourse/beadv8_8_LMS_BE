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
 *     <li>가져가기({@link #claim}): <b>문서 단위</b>다. 한 문서의 대기 요청을 모두 함께 가져가고, 다음 시도 시각을 임대 끝으로 미룬다.
 *     임대 중이거나 다시 시도를 기다리는 요청이 하나라도 있는 문서는 가져가지 않는다. 그래서 한 문서를 두 실행이 함께 처리하지 않는다
 *     (먼저 읽은 옛 상태가 나중에 덮어쓰는 일이 없다). 가져가기는 부르는 쪽이 연 트랜잭션에서 {@link #lockClaims}로 한 줄로 세운다.</li>
 *     <li>ES 호출은 트랜잭션 밖에서 하므로 DB 연결을 붙잡지 않는다. 실행 중 pod가 죽으면 임대가 끝난 뒤 다른 pod가 다시 가져간다.</li>
 *     <li>끝내기·다시 시도·돌려주기는 <b>내 임대일 때만</b> 바꾼다({@code next_attempt_at}이 내가 정한 임대 끝과 같다). 임대가 끝나 다른
 *     실행이 가져간 요청을 늦게 끝난 실행이 덮어쓰지 않는다.</li>
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
     * 여러 실행(pod)의 가져가기를 한 줄로 세운다. {@link #claim}과 같은 트랜잭션에서 먼저 부른다. 트랜잭션이 끝나면 풀린다.
     *
     * @param lockKey 앱 전체에서 겹치지 않는 advisory lock 번호
     */
    public void lockClaims(long lockKey) {
        jdbc.query("SELECT pg_advisory_xact_lock(:lockKey)", new MapSqlParameterSource("lockKey", lockKey), rs -> null);
    }

    /**
     * 실행할 문서를 가져간다. 대기 요청이 모두 시도 시각에 닿은 문서를 오래된 순으로 {@code limit}개 고르고, 그 문서들의 대기 요청을 모두
     * 시도 횟수 하나 올려 {@code leaseUntil}까지 임대한다.
     *
     * @param types 가져갈 종류(처리기가 있는 종류만). 비어 있으면 빈 목록
     * @param limit 가져갈 문서 수
     * @return 가져간 요청(ID 순)
     */
    public List<IndexTask> claim(Collection<IndexType> types, int limit, LocalDateTime now, LocalDateTime leaseUntil) {
        if (types.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                WITH docs AS (
                    SELECT index_type, doc_id FROM chatbot.index_task
                    WHERE done_at IS NULL AND index_type IN (:types)
                    GROUP BY index_type, doc_id
                    HAVING max(next_attempt_at) <= :now
                    ORDER BY min(next_attempt_at), min(id)
                    LIMIT :limit
                )
                UPDATE chatbot.index_task t SET attempts = t.attempts + 1, next_attempt_at = :leaseUntil
                FROM docs
                WHERE t.index_type = docs.index_type AND t.doc_id = docs.doc_id AND t.done_at IS NULL
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

    /** 내 임대인 요청을 끝낸다 */
    public void done(Collection<Long> ids, LocalDateTime now, LocalDateTime leaseUntil) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update("""
                UPDATE chatbot.index_task SET done_at = :now, last_error = NULL
                WHERE id IN (:ids) AND done_at IS NULL AND next_attempt_at = :leaseUntil
                """, new MapSqlParameterSource().addValue("ids", ids).addValue("now", now).addValue("leaseUntil", leaseUntil));
    }

    /** 내 임대인 실패한 요청을 다음 시도 시각으로 미룬다 */
    public void retryLater(Collection<Long> ids, LocalDateTime nextAttemptAt, String error, LocalDateTime leaseUntil) {
        if (ids.isEmpty()) {
            return;
        }
        String trimmed = error.length() > ERROR_MAX_LENGTH ? error.substring(0, ERROR_MAX_LENGTH) : error;
        jdbc.update("""
                UPDATE chatbot.index_task SET next_attempt_at = :next, last_error = :error
                WHERE id IN (:ids) AND done_at IS NULL AND next_attempt_at = :leaseUntil
                """, new MapSqlParameterSource().addValue("ids", ids).addValue("next", nextAttemptAt)
                .addValue("error", trimmed).addValue("leaseUntil", leaseUntil));
    }

    /** 처리하지 못한 내 임대 요청을 돌려준다. 시도로 세지 않고 바로 다시 가져갈 수 있게 한다 */
    public void release(Collection<Long> ids, LocalDateTime now, LocalDateTime leaseUntil) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update("""
                UPDATE chatbot.index_task SET next_attempt_at = :now, attempts = attempts - 1
                WHERE id IN (:ids) AND done_at IS NULL AND next_attempt_at = :leaseUntil
                """, new MapSqlParameterSource().addValue("ids", ids).addValue("now", now).addValue("leaseUntil", leaseUntil));
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
