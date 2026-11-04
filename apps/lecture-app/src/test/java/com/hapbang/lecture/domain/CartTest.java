package com.hapbang.lecture.domain;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CartTest {

    private static final Long BUYER_ID = OTHER_USER_ID;

    @Test
    void 판매_중인_강의를_담는다() {
        Cart cart = Cart.create(BUYER_ID, NOW);

        cart.add(withId(onSaleLecture(), 10L), NOW.plusMinutes(1));

        assertThat(cart.getItems()).extracting(CartItem::getLectureId).containsExactly(10L);
        assertThat(cart.getUpdatedAt()).isEqualTo(NOW.plusMinutes(1));
    }

    @Test
    void 본인_강의는_담을_수_없다() {
        Cart cart = Cart.create(SELLER_ID, NOW);

        assertThatThrownBy(() -> cart.add(withId(onSaleLecture(), 10L), NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.OWN_LECTURE_NOT_PURCHASABLE);
    }

    @Test
    void 판매_중이_아닌_강의는_없는_강의로_보고_담을_수_없다() {
        Lecture deleted = onSaleLecture();
        deleted.delete(SELLER_ID, NOW);
        Cart cart = Cart.create(BUYER_ID, NOW);

        for (Lecture lecture : new Lecture[]{pendingLecture(), rejectedLecture(), deleted}) {
            assertThatThrownBy(() -> cart.add(withId(lecture, 10L), NOW))
                    .isInstanceOf(LectureException.class)
                    .extracting("errorCode").isEqualTo(LectureErrorCode.LECTURE_NOT_FOUND);
        }
        assertThat(cart.getItems()).isEmpty();
    }

    @Test
    void 이미_담은_강의는_다시_담을_수_없다() {
        Cart cart = Cart.create(BUYER_ID, NOW);
        cart.add(withId(onSaleLecture(), 10L), NOW);

        assertThatThrownBy(() -> cart.add(withId(onSaleLecture(), 10L), NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.ALREADY_IN_CART);
    }

    @Test
    void 뺀_강의는_목록에서_사라지고_다시_담을_수_있다() {
        Cart cart = Cart.create(BUYER_ID, NOW);
        cart.add(withId(onSaleLecture(), 10L), NOW);
        CartItem removed = cart.getItems().getFirst();

        cart.remove(10L, NOW.plusMinutes(1));

        assertThat(cart.getItems()).isEmpty();
        assertThat(removed.getDeletedAt()).isEqualTo(NOW.plusMinutes(1));

        cart.add(withId(onSaleLecture(), 10L), NOW.plusMinutes(2));

        assertThat(cart.getItems()).extracting(CartItem::getLectureId).containsExactly(10L);
    }

    @Test
    void 담지_않은_강의를_빼면_아무것도_바뀌지_않는다() {
        Cart cart = Cart.create(BUYER_ID, NOW);
        cart.add(withId(onSaleLecture(), 10L), NOW);

        cart.remove(20L, NOW.plusMinutes(1));

        assertThat(cart.getItems()).extracting(CartItem::getLectureId).containsExactly(10L);
        assertThat(cart.getUpdatedAt()).isEqualTo(NOW);
    }
}
