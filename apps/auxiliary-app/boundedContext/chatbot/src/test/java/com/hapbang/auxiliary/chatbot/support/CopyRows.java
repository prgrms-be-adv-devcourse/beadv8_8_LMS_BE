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
        jdbc.execute("TRUNCATE chatbot.appearance_copy, chatbot.appearance_review_copy, chatbot.member_copy, "
                + "chatbot.lecture_copy, chatbot.lecture_purchase_copy, chatbot.cart_item_copy, chatbot.lecture_review_copy, "
                + "chatbot.masked_member, chatbot.index_task");
    }

    public Map<String, Object> appearance(long id) {
        return jdbc.queryForMap("SELECT * FROM chatbot.appearance_copy WHERE id = ?", id);
    }

    public Map<String, Object> review(long reviewId) {
        return jdbc.queryForMap("SELECT * FROM chatbot.appearance_review_copy WHERE review_id = ?", reviewId);
    }

    /** 한 행. 테이블은 스키마 없는 이름, 키는 키 컬럼 */
    public Map<String, Object> row(String table, String key, long id) {
        return jdbc.queryForMap("SELECT * FROM chatbot." + table + " WHERE " + key + " = ?", id);
    }

    public int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM chatbot." + table, Integer.class);
    }

    /** 회원 사본의 분야 목록(JSON 문자열) */
    public String memberCategories(long id) {
        return jdbc.queryForObject("SELECT categories::text FROM chatbot.member_copy WHERE id = ?", String.class, id);
    }

    public List<Long> maskedMembers() {
        return jdbc.queryForList("SELECT user_id FROM chatbot.masked_member ORDER BY user_id", Long.class);
    }

    public List<Map<String, Object>> indexTasks() {
        return jdbc.queryForList("SELECT index_type, doc_id, action, done_at FROM chatbot.index_task ORDER BY id");
    }

    public List<Long> missing(String table, String key) {
        return jdbc.queryForList("SELECT " + key + " FROM chatbot." + table + " WHERE missing_in_source ORDER BY " + key,
                Long.class);
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
