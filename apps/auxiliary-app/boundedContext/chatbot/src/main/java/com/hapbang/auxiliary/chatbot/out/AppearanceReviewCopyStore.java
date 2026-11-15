package com.hapbang.auxiliary.chatbot.out;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.hapbang.auxiliary.chatbot.domain.AppearanceReviewCopy;
import com.hapbang.auxiliary.chatbot.domain.CopyOutcome;

import lombok.RequiredArgsConstructor;

/**
 * 공개된 출연 리뷰 사본 {@code chatbot.appearance_review_copy} (#716). version이 없어 해시가 다를 때만 값을 바꾼다.
 * 나머지 규칙은 {@link AppearanceCopyStore}와 같다.
 */
@Repository
@RequiredArgsConstructor
public class AppearanceReviewCopyStore {

    private static final String APPLY = "(excluded.dto_hash <> c.dto_hash)";

    private static final String UPSERT = """
            INSERT INTO chatbot.appearance_review_copy AS c
                (review_id, appearance_id, reviewee_user_id, rating, dto_hash, missing_in_source, seen_run_id, synced_at)
            VALUES (:reviewId, :appearanceId, :revieweeUserId, :rating, :hash, FALSE, :runId, :now)
            ON CONFLICT (review_id) DO UPDATE SET
                appearance_id = CASE WHEN %1$s THEN excluded.appearance_id ELSE c.appearance_id END,
                reviewee_user_id = CASE WHEN %1$s THEN excluded.reviewee_user_id ELSE c.reviewee_user_id END,
                rating = CASE WHEN %1$s THEN excluded.rating ELSE c.rating END,
                dto_hash = CASE WHEN %1$s THEN excluded.dto_hash ELSE c.dto_hash END,
                missing_in_source = FALSE,
                seen_run_id = COALESCE(excluded.seen_run_id, c.seen_run_id),
                synced_at = excluded.synced_at
            WHERE %1$s OR excluded.seen_run_id IS NOT NULL
            RETURNING old.dto_hash AS old_hash
            """.formatted(APPLY);

    private static final String CURRENT = "SELECT dto_hash AS old_hash FROM chatbot.appearance_review_copy "
            + "WHERE review_id = :reviewId";

    private static final String MARK_MISSING = """
            UPDATE chatbot.appearance_review_copy SET missing_in_source = TRUE
            WHERE missing_in_source = FALSE
              AND (seen_run_id IS NULL OR seen_run_id <> :runId)
              AND synced_at < :startedAt
            RETURNING review_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 받은 값을 반영한다.
     *
     * @param runId 싱크 회차 ID. 이벤트면 null
     */
    public CopyWrite save(AppearanceReviewCopy copy, Long runId, LocalDateTime now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("reviewId", copy.reviewId())
                .addValue("appearanceId", copy.appearanceId())
                .addValue("revieweeUserId", copy.revieweeUserId())
                .addValue("rating", copy.rating())
                .addValue("hash", copy.hash())
                .addValue("runId", runId)
                .addValue("now", now);
        RowMapper<CopyWrite> outcome = (rs, row) ->
                new CopyWrite(CopyOutcome.decide(null, rs.getString("old_hash"), null, copy.hash()), null);
        List<CopyWrite> written = jdbc.query(UPSERT, params, outcome);
        return written.isEmpty() ? jdbc.queryForObject(CURRENT, params, outcome) : written.getFirst();
    }

    /** 끝까지 성공한 회차의 끝에 원본에 없던 행을 표시한다. 새로 표시한 행을 돌려준다 */
    public List<MissingCopy> markMissing(long runId, LocalDateTime startedAt) {
        return jdbc.query(MARK_MISSING, new MapSqlParameterSource().addValue("runId", runId).addValue("startedAt", startedAt),
                (rs, row) -> new MissingCopy(rs.getLong("review_id"), null));
    }
}
