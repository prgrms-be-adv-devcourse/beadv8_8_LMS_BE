package com.hapbang.lecture.out;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class LectureOrderRepositoryTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
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

        assertThatThrownBy(() -> lectureOrderRepository.saveAndFlush(order()))
                .isInstanceOf(DataIntegrityViolationException.class);
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

    private static LectureOrder order() {
        return LectureOrder.place(BUYER_ID, List.of(withId(onSaleLecture(), LECTURE_ID)), Set.of(), NOW);
    }

    private long countRows(String sql) {
        return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
