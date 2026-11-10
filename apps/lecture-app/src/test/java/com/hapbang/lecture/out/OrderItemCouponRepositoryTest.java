package com.hapbang.lecture.out;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItemCoupon;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

// 주문 항목 쿠폰 계산 근거의 저장과 DB 제약, 쿠폰 없는 항목의 할인 기본값
@RepositoryTest
class OrderItemCouponRepositoryTest {

    @Autowired
    private OrderItemCouponRepository orderItemCouponRepository;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private EntityManager entityManager;

    private Long orderItemId;

    @BeforeEach
    void setUp() {
        LectureOrder order = lectureOrderRepository.saveAndFlush(
                LectureOrderFixture.pending(OTHER_USER_ID, List.of(withId(onSaleLecture(), 10L)), NOW));
        orderItemId = order.getItems().getFirst().getId();
    }

    @Test
    void 항목의_쿠폰을_적용_순서대로_읽고_쿠폰_없는_항목은_할인_0이다() {
        orderItemCouponRepository.save(coupon(12L, 2));
        orderItemCouponRepository.save(coupon(11L, 1));
        flushAndClear();

        assertThat(orderItemCouponRepository.findByOrderItemIdInOrderByOrderItemIdAscApplyOrderAsc(
                List.of(orderItemId)))
                .extracting(OrderItemCoupon::getMemberCouponId, OrderItemCoupon::getReservedGeneration)
                .containsExactly(tuple(11L, null), tuple(12L, null));
        // 기본값 0: 쿠폰 없이 주문한(또는 컬럼 추가 전 옛 코드가 넣은) 항목은 할인 0·기존 금액이다
        assertThat(entityManager.createNativeQuery("select discount_hp + platform_discount_hp + seller_discount_hp "
                + "from lectures.order_item where id = " + orderItemId).getSingleResult())
                .isEqualTo(0L);
    }

    @Test
    void 한_항목에_같은_발급_쿠폰을_두_번_저장하면_유일_제약에_걸린다() {
        orderItemCouponRepository.saveAndFlush(coupon(11L, 1));

        assertViolates(() -> orderItemCouponRepository.saveAndFlush(coupon(11L, 2)),
                "uk_order_item_coupon_item_member_coupon");
    }

    @Test
    void 한_항목에_같은_적용_순서를_두_번_저장하면_유일_제약에_걸린다() {
        orderItemCouponRepository.saveAndFlush(coupon(11L, 1));

        assertViolates(() -> orderItemCouponRepository.saveAndFlush(coupon(12L, 1)),
                "uk_order_item_coupon_item_apply_order");
    }

    @Test
    void 할인이_부담_몫의_합과_다르면_DB가_거부한다() {
        // 도메인 검사를 우회한 쓰기도 DB가 막는다
        assertViolates(() -> entityManager.createNativeQuery("""
                        insert into lectures.order_item_coupon (order_item_id, member_coupon_id, coupon_id, coupon_type,
                            apply_order, base_amount_hp, discount_hp, platform_amount_hp, seller_amount_hp,
                            expected_generation, policy_version)
                        values (:itemId, 11, 21, 'SELLER', 1, 5000, 3000, 0, 2000, 3, 1)
                        """).setParameter("itemId", orderItemId).executeUpdate(),
                "ck_order_item_coupon_discount_split");
    }

    @Test
    void 주문_항목의_할인_합이_부담_몫의_합과_다르면_DB가_거부한다() {
        assertViolates(() -> updateItemDiscount(3_000L, 0L, 2_000L), "ck_order_item_discount_split");
    }

    @Test
    void 주문_항목의_할인_합이_가격보다_크면_DB가_거부한다() {
        assertViolates(() -> updateItemDiscount(PRICE_HP + 1, 0L, PRICE_HP + 1), "ck_order_item_discount_range");
    }

    private int updateItemDiscount(long discount, long platform, long seller) {
        return entityManager.createNativeQuery("update lectures.order_item set discount_hp = :d, "
                        + "platform_discount_hp = :f, seller_discount_hp = :s where id = :id")
                .setParameter("d", discount).setParameter("f", platform).setParameter("s", seller)
                .setParameter("id", orderItemId).executeUpdate();
    }

    private OrderItemCoupon coupon(Long memberCouponId, int applyOrder) {
        return OrderItemCoupon.create(orderItemId, memberCouponId, 21L, "SELLER", applyOrder, 5_000L, 1_000L, 0L,
                1_000L, 3L, 1);
    }

    private static void assertViolates(Runnable write, String constraintName) {
        assertThatThrownBy(write::run)
                .satisfies(error -> assertThat(constraintName(error)).isEqualTo(constraintName));
    }

    private static String constraintName(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        throw new AssertionError("제약 위반이 아닙니다: " + error, error);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
