package com.hapbang.auxiliary.search.lecture.out;

import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;

import lombok.RequiredArgsConstructor;

/**
 * 강의 리뷰 별점 사본 {@code search.lecture_review_copy}(#882 L7·Q4). 강의별 평균 평점·리뷰 수를 다시 셀 때 쓴다.
 * <p>
 * 리뷰 version이 더 클 때만 반영한다. 순서가 뒤바뀐 이벤트·같은 이벤트 두 번·지운 뒤 늦게 온 옛 이벤트는 아무것도 바꾸지 않는다. 평점 반영 여부
 * ({@code counted})는 강의가 판단한 값 그대로 담는다(지운 리뷰, 반환 처리 중·반환된 주문 항목의 리뷰는 {@code false}).
 */
@Repository
@RequiredArgsConstructor
public class LectureReviewCopyStore {

    private static final String UPSERT = """
            INSERT INTO search.lecture_review_copy (id, lecture_id, rating, counted, deleted, version, synced_at)
            VALUES (:id, :lectureId, :rating, :counted, :deleted, :version, :now)
            ON CONFLICT (id) DO UPDATE SET
                lecture_id = excluded.lecture_id,
                rating = excluded.rating,
                counted = excluded.counted,
                deleted = excluded.deleted,
                version = excluded.version,
                synced_at = excluded.synced_at
            WHERE search.lecture_review_copy.version < excluded.version
            """;

    private static final String RATING = """
            SELECT avg(rating) AS average, count(*) AS review_count
            FROM search.lecture_review_copy
            WHERE lecture_id = :lectureId AND counted
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 리뷰를 사본에 반영한다.
     *
     * @return 반영했으면(처음 받은 리뷰 또는 더 새 version) true. 같거나 옛 version이면 false
     * @throws NullPointerException 리뷰 ID·강의 ID가 없는 경우
     */
    public boolean apply(LectureReviewSyncDto review, LocalDateTime now) {
        Objects.requireNonNull(review.id(), "리뷰 ID는 필수입니다.");
        Objects.requireNonNull(review.lectureId(), "강의 ID는 필수입니다.");
        return jdbc.update(UPSERT, new MapSqlParameterSource()
                .addValue("id", review.id())
                .addValue("lectureId", review.lectureId())
                .addValue("rating", review.rating())
                .addValue("counted", review.counted())
                .addValue("deleted", review.deleted())
                .addValue("version", review.version())
                .addValue("now", now)) == 1;
    }

    /** 강의의 평균 평점·리뷰 수. 평점에 반영되는 리뷰({@code counted})만 센다 */
    public LectureRating ratingOf(long lectureId) {
        return jdbc.queryForObject(RATING, new MapSqlParameterSource("lectureId", lectureId), (rs, rowNum) -> {
            long count = rs.getLong("review_count");
            return count == 0 ? LectureRating.NONE : new LectureRating(rs.getDouble("average"), count);
        });
    }
}
