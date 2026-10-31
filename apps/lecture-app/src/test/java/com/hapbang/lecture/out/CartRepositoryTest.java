package com.hapbang.lecture.out;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.hapbang.lecture.domain.Cart;
import com.hapbang.lecture.domain.CartItem;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class CartRepositoryTest {

    private static final Long BUYER_ID = OTHER_USER_ID;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 장바구니는_없을_때만_만들고_같은_회원에게_하나만_남는다() {
        cartRepository.insertIfAbsent(BUYER_ID, NOW);
        cartRepository.insertIfAbsent(BUYER_ID, NOW.plusMinutes(1));

        Cart cart = cartRepository.findByUserId(BUYER_ID).orElseThrow();
        assertThat(cart.getCreatedAt()).isEqualTo(NOW);
        assertThat(countRows("select count(*) from lectures.cart where user_id = " + BUYER_ID)).isEqualTo(1);
    }

    @Test
    void 담은_항목을_최근_담은_순으로_읽는다() {
        Cart cart = cartRepository.save(Cart.create(BUYER_ID, NOW));
        cart.add(withId(onSaleLecture(), 10L), NOW);
        cart.add(withId(onSaleLecture(), 20L), NOW.plusMinutes(1));
        flushAndClear();

        Cart found = cartRepository.findByUserId(BUYER_ID).orElseThrow();

        assertThat(found.getItems()).extracting(CartItem::getLectureId).containsExactly(20L, 10L);
    }

    @Test
    void 뺀_항목은_읽히지_않고_삭제_표시로_남으며_같은_강의를_다시_담을_수_있다() {
        Cart cart = cartRepository.save(Cart.create(BUYER_ID, NOW));
        cart.add(withId(onSaleLecture(), 10L), NOW);
        flushAndClear();

        cartRepository.findByUserId(BUYER_ID).orElseThrow().remove(10L, NOW.plusMinutes(1));
        flushAndClear();

        assertThat(cartRepository.findByUserId(BUYER_ID).orElseThrow().getItems()).isEmpty();
        assertThat(countRows("select count(*) from lectures.cart_item where deleted_at is not null")).isEqualTo(1);

        cartRepository.findByUserId(BUYER_ID).orElseThrow().add(withId(onSaleLecture(), 10L), NOW.plusMinutes(2));
        flushAndClear();

        assertThat(cartRepository.findByUserId(BUYER_ID).orElseThrow().getItems())
                .extracting(CartItem::getLectureId).containsExactly(10L);
    }

    @Test
    void 다른_요청이_먼저_담은_강의를_또_담으면_유일_인덱스에_걸린다() {
        Long cartId = cartRepository.save(Cart.create(BUYER_ID, NOW)).getId();
        flushAndClear();
        Cart cart = cartRepository.findByUserId(BUYER_ID).orElseThrow();
        cart.getItems();
        // 같은 강의를 동시에 담은 다른 요청이 먼저 커밋한 상황
        entityManager.createNativeQuery("insert into lectures.cart_item (cart_id, lecture_id, created_at, updated_at) "
                        + "values (:cartId, 10, now(), now())")
                .setParameter("cartId", cartId)
                .executeUpdate();

        cart.add(withId(onSaleLecture(), 10L), NOW);

        assertThatThrownBy(() -> cartRepository.flush()).isInstanceOf(DataIntegrityViolationException.class);
    }

    private long countRows(String sql) {
        return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
