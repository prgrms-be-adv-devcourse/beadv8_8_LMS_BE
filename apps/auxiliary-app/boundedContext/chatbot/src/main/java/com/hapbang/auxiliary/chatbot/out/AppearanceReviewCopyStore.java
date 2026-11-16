package com.hapbang.auxiliary.chatbot.out;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.hapbang.auxiliary.chatbot.domain.AppearanceReviewCopy;
import com.hapbang.auxiliary.chatbot.domain.ApplySource;
import com.hapbang.auxiliary.chatbot.domain.CopyWrite;

import lombok.RequiredArgsConstructor;

/**
 * 공개된 출연 리뷰 사본 {@code chatbot.appearance_review_copy} (#716). version이 없어 해시가 다를 때만 값을 바꾼다.
 * 나머지 규칙은 {@link AppearanceCopyStore}와 같고, SQL은 {@link CopyTable#withoutVersion}이 만든다.
 */
@Repository
@RequiredArgsConstructor
public class AppearanceReviewCopyStore {

    static final CopyTable TABLE = CopyTable.withoutVersion("appearance_review_copy", "review_id",
            List.of("appearance_id", "reviewee_user_id", "rating"), true);

    private final NamedParameterJdbcTemplate jdbc;

    /** 받은 값을 반영한다 */
    public CopyWrite save(AppearanceReviewCopy copy, ApplySource source, LocalDateTime now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("review_id", copy.reviewId())
                .addValue("appearance_id", copy.appearanceId())
                .addValue("reviewee_user_id", copy.revieweeUserId())
                .addValue("rating", copy.rating());
        return TABLE.save(jdbc, params, null, copy.hash(), source.runId(), now);
    }

    /** 끝까지 성공한 회차의 끝에 원본에 없던 행을 표시한다. 새로 표시한 행을 돌려준다 */
    public List<MissingCopy> markMissing(long runId, LocalDateTime startedAt) {
        return TABLE.markMissing(jdbc, runId, startedAt);
    }
}
