package com.hapbang.lecture.out;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class LectureOrderRepositoryTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long OTHER_BUYER_ID = 4L;
    private static final Long LECTURE_ID = 10L;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 주문과_항목을_저장하고_구매자_본인의_주문만_찾는다() {
        Long orderId = lectureOrderRepository.save(order()).getId();
        flushAndClear();

        LectureOrder found = lectureOrderRepository.findByIdAndBuyerUserId(orderId, BUYER_ID).orElseThrow();

        assertThat(found.getItems()).extracting(OrderItem::getLectureId).containsExactly(LECTURE_ID);
        assertThat(lectureOrderRepository.findByIdAndBuyerUserId(orderId, SELLER_ID)).isEmpty();
    }

    @Test
    void 보유_중인_강의를_다시_저장하면_유일_인덱스에_걸린다() {
        lectureOrderRepository.save(order());
        flushAndClear();

        // 주문 생성은 이 제약 이름으로 동시 주문을 구분해 ALREADY_OWNED_LECTURE로 응답한다 (D45)
        assertThatThrownBy(() -> lectureOrderRepository.saveAndFlush(order()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .cause().isInstanceOf(ConstraintViolationException.class)
                .extracting("constraintName").isEqualTo("uk_order_item_buyer_lecture_owned");
    }

    @Test
    void 구매_취소한_강의는_다시_저장할_수_있다() {
        LectureOrder canceled = lectureOrderRepository.save(order());
        flushAndClear();
        canceled = lectureOrderRepository.findById(canceled.getId()).orElseThrow();
        canceled.cancelItem(canceled.getItems().getFirst().getId(), NOW);
        flushAndClear();

        lectureOrderRepository.saveAndFlush(order());

        assertThat(countRows("select count(*) from lectures.order_item where lecture_id = " + LECTURE_ID))
                .isEqualTo(2);
    }

    @Test
    void 보유_항목을_구매자와_강의로_찾고_취소한_항목은_빼고_찾는다() {
        LectureOrder canceled = lectureOrderRepository.save(order());
        flushAndClear();
        canceled = lectureOrderRepository.findById(canceled.getId()).orElseThrow();
        canceled.cancelItem(canceled.getItems().getFirst().getId(), NOW);
        flushAndClear();
        assertThat(lectureOrderRepository.findHeldItem(BUYER_ID, LECTURE_ID)).isEmpty();

        Long repurchasedItemId = lectureOrderRepository.save(order()).getItems().getFirst().getId();
        flushAndClear();

        assertThat(lectureOrderRepository.findHeldItem(BUYER_ID, LECTURE_ID))
                .hasValueSatisfying(item -> assertThat(item.getId()).isEqualTo(repurchasedItemId));
        assertThat(lectureOrderRepository.findHeldItem(SELLER_ID, LECTURE_ID)).isEmpty();
        assertThat(lectureOrderRepository.findHeldItem(BUYER_ID, 99L)).isEmpty();
    }

    @Test
    void 시청_가능한_항목을_최근_구매순으로_조회한다() {
        lectureOrderRepository.save(order(20L, NOW));
        lectureOrderRepository.save(order(30L, NOW));
        lectureOrderRepository.save(order(40L, NOW.plusDays(1)));
        LectureOrder canceled = lectureOrderRepository.save(order(50L, NOW.plusDays(2)));
        canceled.cancelItem(canceled.getItems().getFirst().getId(), NOW.plusDays(2));
        lectureOrderRepository.save(LectureOrder.place(OTHER_BUYER_ID, List.of(withId(onSaleLecture(), 60L)),
                Set.of(), NOW.plusDays(3)));
        flushAndClear();

        Page<OrderItem> page = lectureOrderRepository.findHeldItems(BUYER_ID, PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(OrderItem::getLectureId).containsExactly(40L, 30L);
        assertThat(lectureOrderRepository.findHeldItems(BUYER_ID, PageRequest.of(1, 2)).getContent())
                .extracting(OrderItem::getLectureId).containsExactly(20L);
    }

    @Test
    void 구매자가_보유한_강의_ID만_찾는다() {
        lectureOrderRepository.save(order(20L, NOW));
        LectureOrder confirmed = lectureOrderRepository.save(order(30L, NOW));
        confirmed.confirmItem(confirmed.getItems().getFirst().getId(), NOW);
        LectureOrder canceled = lectureOrderRepository.save(order(40L, NOW));
        canceled.cancelItem(canceled.getItems().getFirst().getId(), NOW);
        lectureOrderRepository.save(LectureOrder.place(OTHER_BUYER_ID, List.of(withId(onSaleLecture(), 50L)),
                Set.of(), NOW));
        flushAndClear();

        Set<Long> held = lectureOrderRepository.findHeldLectureIds(BUYER_ID, List.of(20L, 30L, 40L, 50L, 99L));

        assertThat(held).containsExactlyInAnyOrder(20L, 30L);
    }

    @Test
    void 기한이_지난_구매_항목_ID만_자동_구매_확정_시각_순으로_조회한다() {
        // 구매일 + 8일 00:00이 자동 구매 확정 시각이다. 10-01 구매분은 10-09, 10-02 구매분은 10-10 00:00
        LocalDateTime oct1 = LocalDateTime.of(2026, 10, 1, 10, 0);
        LocalDateTime now = LocalDateTime.of(2026, 10, 10, 0, 0);
        Long laterDue = itemId(lectureOrderRepository.save(order(20L, oct1.plusDays(1))));
        Long sameDayLaterDue = itemId(lectureOrderRepository.save(order(30L, LocalDateTime.of(2026, 10, 2, 23, 0))));
        Long earlierDue = itemId(lectureOrderRepository.save(order(40L, oct1)));
        lectureOrderRepository.save(order(50L, LocalDateTime.of(2026, 10, 3, 0, 0)));
        LectureOrder confirmed = lectureOrderRepository.save(order(60L, oct1));
        confirmed.confirmItem(itemId(confirmed), oct1);
        LectureOrder canceled = lectureOrderRepository.save(order(70L, oct1));
        canceled.cancelItem(itemId(canceled), oct1);
        flushAndClear();

        List<Long> dueItemIds = lectureOrderRepository.findAutoConfirmDueItemIds(now);

        // 기한이 now와 같은 10-02 구매분까지 대상이고, 10-03 구매분(10-11 00:00)과 확정·취소 항목은 빠진다.
        // 기한이 같으면 ID 순이다
        assertThat(dueItemIds).containsExactly(earlierDue, laterDue, sameDayLaterDue);
    }

    @Test
    void 주문_항목_ID로_주문을_찾는다() {
        LectureOrder saved = lectureOrderRepository.save(LectureOrder.place(BUYER_ID,
                List.of(withId(onSaleLecture(), 20L), withId(onSaleLecture(), 30L)), Set.of(), NOW));
        Long secondItemId = saved.getItems().get(1).getId();
        flushAndClear();

        assertThat(lectureOrderRepository.findByItemId(secondItemId))
                .hasValueSatisfying(order -> assertThat(order.getId()).isEqualTo(saved.getId()));
        assertThat(lectureOrderRepository.findByItemId(99_999L)).isEmpty();
    }

    private static Long itemId(LectureOrder order) {
        return order.getItems().getFirst().getId();
    }

    private static LectureOrder order() {
        return order(LECTURE_ID, NOW);
    }

    private static LectureOrder order(Long lectureId, LocalDateTime purchasedAt) {
        return LectureOrder.place(BUYER_ID, List.of(withId(onSaleLecture(), lectureId)), Set.of(), purchasedAt);
    }

    private long countRows(String sql) {
        return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
