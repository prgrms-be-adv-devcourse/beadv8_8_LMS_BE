package com.hapbang.lecture.out;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.lecture.domain.ContentType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureContent;
import com.hapbang.lecture.domain.LectureContentSpec;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureReview;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class LectureRepositoryTest {

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private LectureReviewRepository lectureReviewRepository;

    @Autowired
    private EntityManager entityManager;

    private long nextBuyerId = 10L;

    @Test
    void 강의를_콘텐츠와_함께_저장하고_순서대로_읽는다() {
        Long lectureId = lectureRepository.save(pendingLecture()).getId();
        flushAndClear();

        Lecture found = lectureRepository.findById(lectureId).orElseThrow();

        assertThat(found.getTitle()).isEqualTo(TITLE);
        assertThat(found.getVersion()).isNotNull();
        assertThat(found.getContents())
                .extracting(LectureContent::getSequence, LectureContent::getFileId)
                .containsExactly(tuple(1, 101L), tuple(2, 102L));
    }

    @Test
    void 콘텐츠를_바꾸면_새_콘텐츠만_읽히고_기존_행은_삭제_표시로_남는다() {
        Long lectureId = lectureRepository.save(rejectedLecture()).getId();
        flushAndClear();

        Lecture lecture = lectureRepository.findById(lectureId).orElseThrow();
        lecture.replaceContents(SELLER_ID,
                List.of(new LectureContentSpec(201L, "1강 다시 찍음", ContentType.VIDEO)), NOW.plusDays(1));
        flushAndClear();

        Lecture found = lectureRepository.findById(lectureId).orElseThrow();
        assertThat(found.getContents())
                .extracting(LectureContent::getSequence, LectureContent::getFileId)
                .containsExactly(tuple(1, 201L));
        assertThat(countContents(lectureId, true)).isEqualTo(2);
        assertThat(countContents(lectureId, false)).isEqualTo(1);
    }

    @Test
    void 평점순은_평균_리뷰_수_최신순이고_리뷰가_없는_강의는_뒤에_최신순으로_온다() {
        Lecture a = onSaleLectureAt("A", NOW);
        Lecture b = onSaleLectureAt("B", NOW.minusDays(1));
        Lecture c = onSaleLectureAt("C", NOW.minusDays(2));
        Lecture d = onSaleLectureAt("D", NOW.plusDays(2));
        Lecture e = onSaleLectureAt("E", NOW.plusDays(1));
        Lecture f = onSaleLectureAt("F", NOW.plusDays(3));
        Lecture g = onSaleLectureAt("G", NOW.plusHours(1));
        reviews(a, 5, 4);
        reviews(b, 5, 4, 5, 4);
        reviews(c, 5);
        returnedReview(c, 1);
        deletedReview(f, 5);
        reviews(g, 4, 5);
        flushAndClear();

        Page<Lecture> page = lectureRepository.findOnSaleOrderByRating("", PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(Lecture::getTitle)
                .containsExactly("C", "B", "G", "A", "F", "D", "E");
        assertThat(page.getTotalElements()).isEqualTo(7);
    }

    @Test
    void 평점순은_판매_중인_강의만_검색어를_글자_그대로_찾는다() {
        onSaleLectureAt("100% 합격 방송", NOW);
        onSaleLectureAt("1000 합격 방송", NOW);
        Lecture deleted = onSaleLectureAt("200% 삭제된 강의", NOW);
        deleted.delete(SELLER_ID, NOW);
        lectureRepository.save(pendingLecture());
        flushAndClear();

        assertThat(lectureRepository.findOnSaleOrderByRating("0%", PageRequest.of(0, 10)).getContent())
                .extracting(Lecture::getTitle).containsExactly("100% 합격 방송");
        assertThat(lectureRepository.findOnSaleOrderByRating("합격", PageRequest.of(0, 10)).getContent())
                .hasSize(2);
        assertThat(lectureRepository.findOnSaleOrderByRating("", PageRequest.of(0, 10)).getContent())
                .hasSize(2);
    }

    private Lecture onSaleLectureAt(String title, LocalDateTime createdAt) {
        Lecture lecture = Lecture.register(SELLER_ID, title, DESCRIPTION, PRICE_HP, null, contentSpecs(), createdAt);
        lecture.approve(ADMIN_ID, createdAt);
        return lectureRepository.save(lecture);
    }

    /** 구매자마다 구매 확정한 항목으로 리뷰를 하나씩 쓴다 */
    private void reviews(Lecture lecture, int... ratings) {
        for (int rating : ratings) {
            review(lecture, rating);
        }
    }

    private void returnedReview(Lecture lecture, int rating) {
        ReflectionTestUtils.setField(review(lecture, rating).item(), "status", OrderItemStatus.RETURNED);
    }

    private void deletedReview(Lecture lecture, int rating) {
        Written written = review(lecture, rating);
        written.review().delete(written.item().getBuyerUserId(), NOW);
    }

    private Written review(Lecture lecture, int rating) {
        Long buyerUserId = nextBuyerId++;
        LectureOrder order = lectureOrderRepository.save(LectureOrderFixture.order(buyerUserId, List.of(lecture), NOW));
        OrderItem item = order.getItems().getFirst();
        order.confirmItem(item.getId(), NOW);
        LectureReview review = lectureReviewRepository.save(LectureReview.write(item, buyerUserId, rating, null, NOW));
        return new Written(item, review);
    }

    private record Written(OrderItem item, LectureReview review) {
    }

    private long countContents(Long lectureId, boolean deleted) {
        String condition = deleted ? "deleted_at is not null" : "deleted_at is null";
        Number count = (Number) entityManager.createNativeQuery(
                        "select count(*) from lectures.lecture_content where lecture_id = :lectureId and " + condition)
                .setParameter("lectureId", lectureId)
                .getSingleResult();
        return count.longValue();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
