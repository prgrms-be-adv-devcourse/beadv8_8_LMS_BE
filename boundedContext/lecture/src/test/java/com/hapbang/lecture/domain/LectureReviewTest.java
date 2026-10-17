package com.hapbang.lecture.domain;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class LectureReviewTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long LECTURE_ID = 10L;
    private static final Long ORDER_ITEM_ID = 100L;

    @ParameterizedTest
    @EnumSource(value = OrderItemStatus.class, names = {"CONFIRMED", "PAID_OUT"})
    void 구매_확정된_항목과_지급된_항목으로_리뷰를_쓴다(OrderItemStatus status) {
        OrderItem item = item(status);

        LectureReview review = LectureReview.write(item, BUYER_ID, 5, "장비 고르는 데 도움이 됐어요", NOW);

        assertThat(review.getLectureId()).isEqualTo(LECTURE_ID);
        assertThat(review.getOrderItemId()).isEqualTo(ORDER_ITEM_ID);
        assertThat(review.getReviewerUserId()).isEqualTo(BUYER_ID);
        assertThat(review.getRating()).isEqualTo(5);
        assertThat(review.getContent()).isEqualTo("장비 고르는 데 도움이 됐어요");
        assertThat(review.getCreatedAt()).isEqualTo(NOW);
        assertThat(review.getUpdatedAt()).isEqualTo(NOW);
        assertThat(review.isDeleted()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = OrderItemStatus.class, names = {"PURCHASED", "CANCELED", "RETURNED"})
    void 구매_확정_전_취소_반환된_항목으로는_리뷰를_쓸_수_없다(OrderItemStatus status) {
        OrderItem item = item(status);

        assertThatThrownBy(() -> LectureReview.write(item, BUYER_ID, 5, null, NOW))
                .isInstanceOfSatisfying(LectureException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(LectureErrorCode.ORDER_ITEM_NOT_REVIEWABLE));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6})
    void 평점이_1에서_5를_벗어나면_쓸_수_없다(int rating) {
        OrderItem item = item(OrderItemStatus.CONFIRMED);

        assertThatThrownBy(() -> LectureReview.write(item, BUYER_ID, rating, null, NOW))
                .isInstanceOfSatisfying(LectureException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(LectureErrorCode.INVALID_REQUEST));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void 본문이_비었거나_공백이면_null로_저장한다(String content) {
        LectureReview review = LectureReview.write(item(OrderItemStatus.CONFIRMED), BUYER_ID, 4, content, NOW);

        assertThat(review.getContent()).isNull();
    }

    @Test
    void 작성자는_평점과_본문을_고친다() {
        LectureReview review = LectureReview.write(item(OrderItemStatus.CONFIRMED), BUYER_ID, 2, "별로", NOW);

        review.edit(BUYER_ID, 4, " ", NOW.plusDays(1));

        assertThat(review.getRating()).isEqualTo(4);
        assertThat(review.getContent()).isNull();
        assertThat(review.getUpdatedAt()).isEqualTo(NOW.plusDays(1));
    }

    @Test
    void 작성자는_리뷰를_삭제_표시한다() {
        LectureReview review = LectureReview.write(item(OrderItemStatus.CONFIRMED), BUYER_ID, 2, null, NOW);

        review.delete(BUYER_ID, NOW.plusDays(1));

        assertThat(review.isDeleted()).isTrue();
        assertThat(review.getDeletedAt()).isEqualTo(NOW.plusDays(1));
    }

    @Test
    void 작성자가_아니면_수정_삭제할_수_없다() {
        LectureReview review = LectureReview.write(item(OrderItemStatus.CONFIRMED), BUYER_ID, 2, null, NOW);

        assertThatThrownBy(() -> review.edit(4L, 5, null, NOW))
                .isInstanceOfSatisfying(LectureException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(LectureErrorCode.NOT_REVIEW_WRITER));
        assertThatThrownBy(() -> review.delete(4L, NOW))
                .isInstanceOfSatisfying(LectureException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(LectureErrorCode.NOT_REVIEW_WRITER));
        assertThat(review.getRating()).isEqualTo(2);
        assertThat(review.isDeleted()).isFalse();
    }

    /** 상태 전이 API가 없는 상태(반환·지급)도 만들 수 있게 필드를 직접 바꾼다 */
    private static OrderItem item(OrderItemStatus status) {
        LectureOrder order = LectureOrder.place(BUYER_ID, List.of(withId(onSaleLecture(), LECTURE_ID)), Set.of(),
                LocalDateTime.of(2026, 9, 30, 10, 0));
        OrderItem item = order.getItems().getFirst();
        ReflectionTestUtils.setField(item, "id", ORDER_ITEM_ID);
        ReflectionTestUtils.setField(item, "status", status);
        return item;
    }
}
