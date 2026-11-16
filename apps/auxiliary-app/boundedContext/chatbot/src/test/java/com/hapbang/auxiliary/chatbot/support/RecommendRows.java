package com.hapbang.auxiliary.chatbot.support;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.json.JsonMapper;

/**
 * 추천 테스트용 사본 행을 SQL로 바로 넣는다(#718). 반영 규칙(해시·version)은 사본 테스트가 따로 보므로 여기서는 값만 넣는다.
 * 비우기는 {@link CopyRows#clear()}.
 */
public record RecommendRows(JdbcTemplate jdbc) {

    public static final LocalDateTime AT = LocalDateTime.of(2026, 11, 9, 9, 0);
    private static final String HASH = "0".repeat(64);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 보이는 회원(정상 USER) */
    public void member(long id) {
        member(id, "ACTIVE");
    }

    public void member(long id, String status) {
        jdbc.update("""
                INSERT INTO chatbot.member_copy (id, role, status, categories, bio, version, dto_hash, synced_at)
                VALUES (?, 'USER', ?, '[]', NULL, 1, ?, ?)""", id, status, HASH, AT);
    }

    /** 판매 중 강의 */
    public void lecture(long id, long sellerId, String title) {
        lecture(id, sellerId, title, "소개 " + title, List.of(), true, false, false);
    }

    public void lecture(long id, long sellerId, String title, String description, List<String> contentTitles, boolean onSale,
            boolean deleted, boolean missingInSource) {
        jdbc.update("""
                INSERT INTO chatbot.lecture_copy (id, seller_user_id, title, description, content_titles, on_sale, deleted,
                    version, dto_hash, missing_in_source, synced_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, 1, ?, ?, ?)""", id, sellerId, title, description,
                JSON.writeValueAsString(contentTitles), onSale, deleted, HASH, missingInSource, AT);
    }

    /** @param result OWNED, PENDING, CANCELED, RETURNED, FAILED */
    public void purchase(long orderItemId, long buyerId, long lectureId, String result, LocalDateTime at) {
        jdbc.update("""
                INSERT INTO chatbot.lecture_purchase_copy (order_item_id, buyer_user_id, lecture_id, result, ordered_at,
                    purchased_at, version, dto_hash, synced_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)""", orderItemId, buyerId, lectureId, result, at, at, HASH, AT);
    }

    public void cart(long id, long userId, long lectureId, boolean deleted, LocalDateTime at) {
        jdbc.update("""
                INSERT INTO chatbot.cart_item_copy (id, user_id, lecture_id, deleted, added_at, version, dto_hash, synced_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?)""", id, userId, lectureId, deleted, at, HASH, AT);
    }

    /** 평점에 세는 리뷰 */
    public void review(long id, long reviewerId, long lectureId, int rating) {
        jdbc.update("""
                INSERT INTO chatbot.lecture_review_copy (id, lecture_id, reviewer_user_id, rating, counted, deleted, version,
                    dto_hash, synced_at)
                VALUES (?, ?, ?, ?, TRUE, FALSE, 1, ?, ?)""", id, lectureId, reviewerId, rating, HASH, AT);
    }

    /** 보이는 인플루언서(정상 INFLUENCER, #867) */
    public void influencer(long id, List<String> categories, String bio) {
        jdbc.update("""
                INSERT INTO chatbot.member_copy (id, role, status, categories, bio, version, dto_hash, synced_at)
                VALUES (?, 'INFLUENCER', 'ACTIVE', ?::jsonb, ?, 1, ?, ?)""", id, JSON.writeValueAsString(categories), bio, HASH,
                AT);
    }

    /** 두 회원이 함께 마친 출연(#867) */
    public void completedAppearance(long id, long hostId, long guestId) {
        jdbc.update("""
                INSERT INTO chatbot.appearance_copy (id, host_id, guest_id, result, scheduled_at, version, dto_hash, synced_at)
                VALUES (?, ?, ?, 'COMPLETED', ?, 1, ?, ?)""", id, hostId, guestId, AT, HASH, AT);
    }

    /** 받은 출연 리뷰(#867) */
    public void appearanceReview(long reviewId, long appearanceId, long revieweeId, int rating) {
        jdbc.update("""
                INSERT INTO chatbot.appearance_review_copy (review_id, appearance_id, reviewee_user_id, rating, dto_hash, synced_at)
                VALUES (?, ?, ?, ?, ?, ?)""", reviewId, appearanceId, revieweeId, rating, HASH, AT);
    }
}
