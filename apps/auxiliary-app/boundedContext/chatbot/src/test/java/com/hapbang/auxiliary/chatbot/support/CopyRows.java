package com.hapbang.auxiliary.chatbot.support;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.shared.appearance.domain.AppearanceResult;
import com.hapbang.shared.appearance.dto.AppearanceReviewSyncDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncDto;

/**
 * 사본 테이블을 읽고 비운다. 사본은 JPA 엔티티가 아니라 테스트 공통 정리({@code DatabaseCleanUp})가 비우지 않으므로 테스트마다 {@link #clear()}한다.
 */
public record CopyRows(JdbcTemplate jdbc) {

    public static final LocalDateTime SCHEDULED_AT = LocalDateTime.of(2026, 11, 20, 20, 0);

    public void clear() {
        jdbc.execute("TRUNCATE chatbot.appearance_copy, chatbot.appearance_review_copy");
    }

    public Map<String, Object> appearance(long id) {
        return jdbc.queryForMap("SELECT * FROM chatbot.appearance_copy WHERE id = ?", id);
    }

    public Map<String, Object> review(long reviewId) {
        return jdbc.queryForMap("SELECT * FROM chatbot.appearance_review_copy WHERE review_id = ?", reviewId);
    }

    public int appearanceCount() {
        return jdbc.queryForObject("SELECT count(*) FROM chatbot.appearance_copy", Integer.class);
    }

    public List<Long> missingAppearances() {
        return jdbc.queryForList("SELECT id FROM chatbot.appearance_copy WHERE missing_in_source ORDER BY id", Long.class);
    }

    public List<Long> missingReviews() {
        return jdbc.queryForList(
                "SELECT review_id FROM chatbot.appearance_review_copy WHERE missing_in_source ORDER BY review_id", Long.class);
    }

    public static AppearanceSyncDto appearance(long id, AppearanceResult result, long version) {
        return new AppearanceSyncDto(id, 1L, 2L, result, SCHEDULED_AT, version);
    }

    public static AppearanceReviewSyncDto review(long reviewId, long appearanceId, int rating) {
        return new AppearanceReviewSyncDto(reviewId, appearanceId, 2L, rating);
    }
}
