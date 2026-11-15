package com.hapbang.auxiliary.search.lecture.out;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    private static final String RATINGS = """
            SELECT lecture_id, avg(rating) AS average, count(*) AS review_count
            FROM search.lecture_review_copy
            WHERE lecture_id IN (:lectureIds) AND counted
            GROUP BY lecture_id
            """;

    private static final String IDS_UP_TO = """
            SELECT id FROM search.lecture_review_copy WHERE id <= :maxId
            """;

    private static final String DELETE = """
            DELETE FROM search.lecture_review_copy WHERE id IN (:ids)
            """;

    /** 한 번에 지우는 리뷰 수(SQL 인자 수 상한 안) */
    static final int DELETE_CHUNK = 1000;

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

    /**
     * 강의 여러 개의 평균 평점·리뷰 수를 한 번에 센다(#884 처음 적재·평점 맞추기). 평점에 반영되는 리뷰({@code counted})만 센다.
     *
     * @return 강의 ID별 평점. 반영되는 리뷰가 없는 강의는 {@link LectureRating#NONE}이다(넘긴 강의는 모두 들어 있다)
     */
    public Map<Long, LectureRating> ratingsOf(Collection<Long> lectureIds) {
        Map<Long, LectureRating> ratings = new HashMap<>();
        lectureIds.forEach(id -> ratings.put(id, LectureRating.NONE));
        if (lectureIds.isEmpty()) {
            return ratings;
        }
        jdbc.query(RATINGS, new MapSqlParameterSource("lectureIds", lectureIds), rs -> {
            ratings.put(rs.getLong("lecture_id"), new LectureRating(rs.getDouble("average"), rs.getLong("review_count")));
        });
        return ratings;
    }

    /**
     * 원본(리뷰 동기화 API)에 없는 리뷰를 사본에서 지운다(#884 리뷰 보정). 강의는 리뷰를 지우지 않고 {@code deleted}로 남기므로 평소에는 없다. 강의 DB를
     * 되돌리는 등으로 원본 행이 사라진 경우만이다.
     * <p>
     * 원본을 끝까지 읽은 뒤 부른다. 읽는 동안 생긴 리뷰(마지막으로 읽은 ID보다 큰 ID)는 원본에 있어도 이번에 못 읽었을 수 있어 건너뛴다.
     *
     * @param seenIds 이번에 원본에서 읽은 리뷰 ID
     * @param maxId   이번에 원본에서 읽은 가장 큰 리뷰 ID
     * @return 지운 리뷰 ID
     */
    public List<Long> deleteMissing(Set<Long> seenIds, long maxId) {
        List<Long> missing = jdbc.queryForList(IDS_UP_TO, new MapSqlParameterSource("maxId", maxId), Long.class).stream()
                .filter(id -> !seenIds.contains(id)).sorted().toList();
        for (int from = 0; from < missing.size(); from += DELETE_CHUNK) {
            List<Long> chunk = new ArrayList<>(missing.subList(from, Math.min(from + DELETE_CHUNK, missing.size())));
            jdbc.update(DELETE, new MapSqlParameterSource("ids", chunk));
        }
        return missing;
    }
}
