package com.hapbang.lecture.out;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureReview;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class LectureReviewRepositoryTest {

    private static final Long BUYER_ID = OTHER_USER_ID;

    @Autowired
    private LectureReviewRepository lectureReviewRepository;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 같은_회원은_한_강의에_살아_있는_리뷰를_하나만_가진다() {
        Lecture lecture = lectureRepository.save(onSaleLecture());
        OrderItem item = confirmedItem(BUYER_ID, lecture);
        lectureReviewRepository.save(LectureReview.write(item, BUYER_ID, 5, null, NOW));
        flushAndClear();

        // 같은 회원이 같은 강의의 다른 항목(반환 후 재구매)으로 또 쓴 상황
        assertThatThrownBy(() -> insertReview(lecture.getId(), item.getId() + 1000, 3))
                .rootCause().hasMessageContaining("uk_lecture_review_lecture_reviewer");
    }

    @Test
    void 한_주문_항목에는_살아_있는_리뷰가_하나뿐이다() {
        Lecture lecture = lectureRepository.save(onSaleLecture());
        OrderItem item = confirmedItem(BUYER_ID, lecture);
        lectureReviewRepository.save(LectureReview.write(item, BUYER_ID, 5, null, NOW));
        flushAndClear();

        assertThatThrownBy(() -> {
            lectureReviewRepository.save(LectureReview.write(item, 4L, 3, null, NOW));
            lectureReviewRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 삭제한_뒤에는_같은_항목으로_다시_쓴다() {
        Lecture lecture = lectureRepository.save(onSaleLecture());
        OrderItem item = confirmedItem(BUYER_ID, lecture);
        LectureReview review = lectureReviewRepository.save(LectureReview.write(item, BUYER_ID, 5, null, NOW));
        review.delete(BUYER_ID, NOW.plusMinutes(1));
        flushAndClear();

        lectureReviewRepository.save(LectureReview.write(item, BUYER_ID, 2, "다시 보니 아쉬워요", NOW.plusMinutes(2)));
        flushAndClear();

        assertThat(lectureReviewRepository.findAlive(lecture.getId(), BUYER_ID))
                .get().extracting(LectureReview::getRating).isEqualTo(2);
        assertThat(lectureReviewRepository.findByIdAndDeletedAtIsNull(review.getId())).isEmpty();
    }

    @Test
    void 평점이_1에서_5를_벗어나면_DB가_거부한다() {
        Lecture lecture = lectureRepository.save(onSaleLecture());
        OrderItem item = confirmedItem(BUYER_ID, lecture);
        flushAndClear();

        assertThatThrownBy(() -> insertReview(lecture.getId(), item.getId(), 6))
                .rootCause().hasMessageContaining("ck_lecture_review_rating");
    }

    @Test
    void 목록은_삭제된_리뷰와_반환된_항목의_리뷰를_빼고_최신순이다() {
        Lecture lecture = lectureRepository.save(onSaleLecture());
        LectureReview older = review(lecture, BUYER_ID, OrderItemStatus.CONFIRMED, 5, NOW);
        LectureReview newer = review(lecture, 4L, OrderItemStatus.PAID_OUT, 4, NOW.plusMinutes(1));
        review(lecture, 5L, OrderItemStatus.RETURNED, 1, NOW.plusMinutes(2));
        review(lecture, 6L, OrderItemStatus.CONFIRMED, 1, NOW.plusMinutes(3)).delete(6L, NOW.plusMinutes(4));
        // 반환 처리 중부터 빼고, 지급 처리 중은 그대로 보인다 (D59 (3)·(5))
        review(lecture, 7L, OrderItemStatus.RETURN_PENDING, 1, NOW.plusMinutes(5));
        LectureReview payingOut = review(lecture, 8L, OrderItemStatus.PAYOUT_PENDING, 3, NOW.plusMinutes(6));
        flushAndClear();

        assertThat(lectureReviewRepository.findVisible(lecture.getId(), PageRequest.of(0, 10)))
                .extracting(LectureReview::getId)
                .containsExactly(payingOut.getId(), newer.getId(), older.getId());
    }

    @Test
    void 평균_평점과_리뷰_수는_삭제된_리뷰와_반환된_항목의_리뷰를_빼고_강의별로_집계한다() {
        Lecture lecture = lectureRepository.save(onSaleLecture());
        Lecture other = lectureRepository.save(onSaleLecture());
        Lecture noReview = lectureRepository.save(onSaleLecture());
        review(lecture, BUYER_ID, OrderItemStatus.CONFIRMED, 5, NOW);
        review(lecture, 4L, OrderItemStatus.PAID_OUT, 4, NOW);
        review(lecture, 5L, OrderItemStatus.CONFIRMED, 4, NOW);
        review(lecture, 6L, OrderItemStatus.RETURNED, 1, NOW);
        review(lecture, 7L, OrderItemStatus.CONFIRMED, 1, NOW).delete(7L, NOW);
        review(lecture, 8L, OrderItemStatus.RETURN_PENDING, 1, NOW);
        review(other, BUYER_ID, OrderItemStatus.CONFIRMED, 3, NOW);
        flushAndClear();

        List<LectureReviewStats> stats = lectureReviewRepository.summarize(
                List.of(lecture.getId(), other.getId(), noReview.getId()));

        assertThat(stats)
                .extracting(LectureReviewStats::lectureId, LectureReviewStats::averageRating,
                        LectureReviewStats::reviewCount)
                .containsExactlyInAnyOrder(
                        tuple(lecture.getId(), 13.0 / 3, 3L),
                        tuple(other.getId(), 3.0, 1L));
    }

    private LectureReview review(Lecture lecture, Long buyerUserId, OrderItemStatus status, int rating,
                                 LocalDateTime now) {
        OrderItem item = confirmedItem(buyerUserId, lecture);
        LectureReview review = lectureReviewRepository.save(LectureReview.write(item, buyerUserId, rating, null, now));
        // 반환·지급 전이는 아직 API가 없어 필드를 직접 바꾼다
        ReflectionTestUtils.setField(item, "status", status);
        return review;
    }

    private OrderItem confirmedItem(Long buyerUserId, Lecture lecture) {
        LectureOrder order = lectureOrderRepository.save(LectureOrderFixture.order(buyerUserId, List.of(lecture), NOW));
        OrderItem item = order.getItems().getFirst();
        order.confirmItem(item.getId(), NOW);
        lectureOrderRepository.flush();
        return item;
    }

    private void insertReview(Long lectureId, Long orderItemId, int rating) {
        entityManager.createNativeQuery("insert into lectures.lecture_review "
                        + "(lecture_id, order_item_id, reviewer_user_id, rating, created_at, updated_at) "
                        + "values (:lectureId, :orderItemId, :userId, :rating, now(), now())")
                .setParameter("lectureId", lectureId)
                .setParameter("orderItemId", orderItemId)
                .setParameter("userId", BUYER_ID)
                .setParameter("rating", rating)
                .executeUpdate();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
