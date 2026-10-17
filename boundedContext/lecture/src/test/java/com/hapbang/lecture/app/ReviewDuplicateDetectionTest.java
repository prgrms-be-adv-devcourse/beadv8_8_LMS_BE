package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureReview;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.out.LectureReviewRepository;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

// 실제 PostgreSQL이 보낸 무결성 위반으로 유일 인덱스 판별(UniqueIndexViolation)을 확인한다.
@RepositoryTest
class ReviewDuplicateDetectionTest {

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
    void 리뷰_부분_유일_인덱스_위반은_중복으로_판별한다() {
        OrderItem item = confirmedItem();
        lectureReviewRepository.save(LectureReview.write(item, BUYER_ID, 5, null, NOW));
        lectureReviewRepository.flush();

        assertThatThrownBy(() -> {
            lectureReviewRepository.save(LectureReview.write(item, BUYER_ID, 3, null, NOW));
            lectureReviewRepository.flush();
        }).isInstanceOfSatisfying(DataIntegrityViolationException.class,
                exception -> assertThat(isReviewDuplicate(exception)).isTrue());
    }

    @Test
    void 평점_CHECK_위반은_중복으로_보지_않는다() {
        LectureReview review = lectureReviewRepository.save(LectureReview.write(confirmedItem(), BUYER_ID, 5, null, NOW));
        lectureReviewRepository.flush();
        // 도메인 검사를 우회해 DB 제약만 확인한다
        ReflectionTestUtils.setField(review, "rating", 6);

        assertThatThrownBy(() -> lectureReviewRepository.flush())
                .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                        exception -> assertThat(isReviewDuplicate(exception)).isFalse());
    }

    private static boolean isReviewDuplicate(DataIntegrityViolationException exception) {
        return UniqueIndexViolation.isOf(exception, "uk_lecture_review_order_item", "uk_lecture_review_lecture_reviewer");
    }

    private OrderItem confirmedItem() {
        Lecture lecture = lectureRepository.save(onSaleLecture());
        LectureOrder order = lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID, List.of(lecture), NOW));
        OrderItem item = order.getItems().getFirst();
        order.confirmItem(item.getId(), NOW);
        entityManager.flush();
        return item;
    }
}
