package com.hapbang.auxiliary.search.lecture.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.testsupport.RepositoryTest;

/** 강의 리뷰 별점 사본과 강의별 평균·리뷰 수(#882 L7·Q4). 모듈 SQL로 만든 search.lecture_review_copy에 PostgreSQL 컨테이너로 쓴다 */
@RepositoryTest
@Import(LectureReviewCopyStore.class)
class LectureReviewCopyStoreTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 15, 0);
    private static final long LECTURE = 11L;

    @Autowired
    private LectureReviewCopyStore store;

    @Test
    @DisplayName("리뷰가 없는 강의는 평균이 없고 리뷰 수 0이다")
    void noReviews() {
        assertThat(store.ratingOf(LECTURE)).isEqualTo(LectureRating.NONE);
    }

    @Test
    @DisplayName("평점에 반영되는 리뷰만으로 평균·리뷰 수를 센다(다른 강의 리뷰는 섞이지 않는다)")
    void rating() {
        store.apply(review(1L, LECTURE, 5, true, false, 0L), NOW);
        store.apply(review(2L, LECTURE, 4, true, false, 0L), NOW);
        store.apply(review(3L, LECTURE, 1, false, false, 0L), NOW);
        store.apply(review(4L, 12L, 1, true, false, 0L), NOW);

        LectureRating rating = store.ratingOf(LECTURE);

        assertThat(rating.count()).isEqualTo(2);
        assertThat(rating.average()).isCloseTo(4.5, within(1e-9));
    }

    @Test
    @DisplayName("수정·삭제·반환으로 바뀐 리뷰는 더 새 version일 때 반영해 평균이 다시 계산된다")
    void changed() {
        store.apply(review(1L, LECTURE, 5, true, false, 0L), NOW);
        store.apply(review(2L, LECTURE, 3, true, false, 0L), NOW);

        assertThat(store.apply(review(1L, LECTURE, 2, true, false, 1L), NOW)).isTrue();
        assertThat(store.ratingOf(LECTURE).average()).isCloseTo(2.5, within(1e-9));

        // 삭제(counted=false)
        assertThat(store.apply(review(2L, LECTURE, 3, false, true, 1L), NOW)).isTrue();
        assertThat(store.ratingOf(LECTURE)).isEqualTo(new LectureRating(2.0, 1));

        assertThat(store.apply(review(1L, LECTURE, 2, false, false, 2L), NOW)).isTrue();
        assertThat(store.ratingOf(LECTURE)).isEqualTo(LectureRating.NONE);
    }

    @Test
    @DisplayName("순서가 뒤바뀐 옛 version, 같은 이벤트 두 번, 삭제 뒤 늦게 온 작성 이벤트는 반영하지 않는다")
    void staleOrDuplicate() {
        assertThat(store.apply(review(1L, LECTURE, 4, false, true, 2L), NOW)).isTrue();

        assertThat(store.apply(review(1L, LECTURE, 4, true, false, 0L), NOW)).isFalse();
        assertThat(store.apply(review(1L, LECTURE, 5, true, false, 1L), NOW)).isFalse();
        assertThat(store.apply(review(1L, LECTURE, 4, false, true, 2L), NOW)).isFalse();
        assertThat(store.ratingOf(LECTURE)).isEqualTo(LectureRating.NONE);
    }

    private static LectureReviewSyncDto review(long id, long lectureId, int rating, boolean counted, boolean deleted,
            long version) {
        return new LectureReviewSyncDto(id, lectureId, 7L, rating, counted, deleted, version);
    }
}
