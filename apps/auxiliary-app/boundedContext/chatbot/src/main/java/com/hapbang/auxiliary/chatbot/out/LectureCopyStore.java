package com.hapbang.auxiliary.chatbot.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.hapbang.auxiliary.chatbot.domain.LectureCopies;

import lombok.RequiredArgsConstructor;

/**
 * 강의 쪽 사본 4종 {@code chatbot.lecture_copy}·{@code lecture_purchase_copy}·{@code cart_item_copy}·{@code lecture_review_copy}
 * (#717). 반영 규칙은 {@link CopyTable}. 넷 다 원본 없음을 표시한다.
 */
@Repository
@RequiredArgsConstructor
public class LectureCopyStore {

    static final CopyTable LECTURE = new CopyTable("lecture_copy", "id",
            List.of("seller_user_id", "title", "description", "content_titles", "on_sale", "deleted"),
            Set.of("content_titles"), true, true);
    static final CopyTable PURCHASE = new CopyTable("lecture_purchase_copy", "order_item_id",
            List.of("buyer_user_id", "lecture_id", "result", "ordered_at", "purchased_at"), Set.of(), true, true);
    static final CopyTable CART_ITEM = new CopyTable("cart_item_copy", "id",
            List.of("user_id", "lecture_id", "deleted", "added_at"), Set.of(), true, true);
    static final CopyTable REVIEW = new CopyTable("lecture_review_copy", "id",
            List.of("lecture_id", "reviewer_user_id", "rating", "counted", "deleted"), Set.of(), true, true);


    private final NamedParameterJdbcTemplate jdbc;

    /** @param runId 싱크 회차 ID. 이벤트면 null */
    public CopyWrite save(LectureCopies.Lecture copy, Long runId, LocalDateTime now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", copy.id())
                .addValue("seller_user_id", copy.sellerUserId())
                .addValue("title", copy.title())
                .addValue("description", copy.description())
                .addValue("content_titles", JsonColumns.write(copy.contentTitles()))
                .addValue("on_sale", copy.onSale())
                .addValue("deleted", copy.deleted());
        return LECTURE.save(jdbc, params, copy.version(), copy.hash(), runId, now);
    }

    /** @param runId 싱크 회차 ID. 이벤트면 null */
    public CopyWrite save(LectureCopies.Purchase copy, Long runId, LocalDateTime now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("order_item_id", copy.orderItemId())
                .addValue("buyer_user_id", copy.buyerUserId())
                .addValue("lecture_id", copy.lectureId())
                .addValue("result", copy.result().name())
                .addValue("ordered_at", copy.orderedAt())
                .addValue("purchased_at", copy.purchasedAt());
        return PURCHASE.save(jdbc, params, copy.version(), copy.hash(), runId, now);
    }

    /** @param runId 싱크 회차 ID. 이벤트면 null */
    public CopyWrite save(LectureCopies.CartItem copy, Long runId, LocalDateTime now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", copy.id())
                .addValue("user_id", copy.userId())
                .addValue("lecture_id", copy.lectureId())
                .addValue("deleted", copy.deleted())
                .addValue("added_at", copy.addedAt());
        return CART_ITEM.save(jdbc, params, copy.version(), copy.hash(), runId, now);
    }

    /** @param runId 싱크 회차 ID. 이벤트면 null */
    public CopyWrite save(LectureCopies.Review copy, Long runId, LocalDateTime now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", copy.id())
                .addValue("lecture_id", copy.lectureId())
                .addValue("reviewer_user_id", copy.reviewerUserId())
                .addValue("rating", copy.rating())
                .addValue("counted", copy.counted())
                .addValue("deleted", copy.deleted());
        return REVIEW.save(jdbc, params, copy.version(), copy.hash(), runId, now);
    }

    public List<MissingCopy> markMissingLectures(long runId, LocalDateTime startedAt) {
        return LECTURE.markMissing(jdbc, runId, startedAt);
    }

    public List<MissingCopy> markMissingPurchases(long runId, LocalDateTime startedAt) {
        return PURCHASE.markMissing(jdbc, runId, startedAt);
    }

    public List<MissingCopy> markMissingCartItems(long runId, LocalDateTime startedAt) {
        return CART_ITEM.markMissing(jdbc, runId, startedAt);
    }

    public List<MissingCopy> markMissingReviews(long runId, LocalDateTime startedAt) {
        return REVIEW.markMissing(jdbc, runId, startedAt);
    }
}
