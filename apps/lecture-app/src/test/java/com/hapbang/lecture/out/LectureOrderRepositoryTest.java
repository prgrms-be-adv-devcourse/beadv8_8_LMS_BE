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
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.domain.ObjectionStatus;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;
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
    void 결제_취소_처리_중인_강의를_다시_저장하면_유일_인덱스에_걸린다() {
        LectureOrder compensating = lectureOrderRepository.save(pending(LECTURE_ID));
        compensating.startHoldCompensation();
        flushAndClear();

        assertThatThrownBy(() -> lectureOrderRepository.saveAndFlush(pending(LECTURE_ID)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .cause().isInstanceOf(ConstraintViolationException.class)
                .extracting("constraintName").isEqualTo("uk_order_item_buyer_lecture_owned");
    }

    @Test
    void 결제_실패한_강의는_다시_저장할_수_있다() {
        LectureOrder failed = lectureOrderRepository.save(pending(LECTURE_ID));
        failed.markHoldFailed();
        flushAndClear();

        lectureOrderRepository.saveAndFlush(pending(LECTURE_ID));

        assertThat(countRows("select count(*) from lectures.order_item where lecture_id = " + LECTURE_ID))
                .isEqualTo(2);
    }

    @Test
    void 결제_처리_중인_강의는_보유_강의_ID에는_들고_시청_가능_항목에는_없다() {
        save(pending(20L));
        save(order(30L, NOW));
        flushAndClear();

        assertThat(lectureOrderRepository.findHeldLectureIds(BUYER_ID, List.of(20L, 30L)))
                .containsExactlyInAnyOrder(20L, 30L);
        assertThat(lectureOrderRepository.findViewableItem(BUYER_ID, 20L)).isEmpty();
        assertThat(lectureOrderRepository.findViewableItem(BUYER_ID, 30L)).isPresent();
        assertThat(lectureOrderRepository.findViewableItems(BUYER_ID, PageRequest.of(0, 10)).getContent())
                .extracting(OrderItem::getLectureId).containsExactly(30L);
    }

    @Test
    void 판매_내역에서_결제가_끝나지_않은_항목을_뺀다() {
        Long purchased = itemId(save(order(20L, NOW)));
        save(pending(30L));
        LectureOrder failed = save(pending(40L));
        failed.markHoldFailed();
        flushAndClear();

        assertThat(lectureOrderRepository.findSales(SELLER_ID, PageRequest.of(0, 10)).getContent())
                .extracting(OrderItem::getId).containsExactly(purchased);
    }

    @Test
    void 구매_취소한_강의는_다시_저장할_수_있다() {
        LectureOrder canceled = lectureOrderRepository.save(order());
        flushAndClear();
        canceled = lectureOrderRepository.findById(canceled.getId()).orElseThrow();
        LectureOrderFixture.cancel(canceled, canceled.getItems().getFirst().getId(), NOW);
        flushAndClear();

        lectureOrderRepository.saveAndFlush(order());

        assertThat(countRows("select count(*) from lectures.order_item where lecture_id = " + LECTURE_ID))
                .isEqualTo(2);
    }

    @Test
    void 시청_가능한_항목을_구매자와_강의로_찾고_취소한_항목은_빼고_찾는다() {
        LectureOrder canceled = lectureOrderRepository.save(order());
        flushAndClear();
        canceled = lectureOrderRepository.findById(canceled.getId()).orElseThrow();
        LectureOrderFixture.cancel(canceled, canceled.getItems().getFirst().getId(), NOW);
        flushAndClear();
        assertThat(lectureOrderRepository.findViewableItem(BUYER_ID, LECTURE_ID)).isEmpty();

        Long repurchasedItemId = lectureOrderRepository.save(order()).getItems().getFirst().getId();
        flushAndClear();

        assertThat(lectureOrderRepository.findViewableItem(BUYER_ID, LECTURE_ID))
                .hasValueSatisfying(item -> assertThat(item.getId()).isEqualTo(repurchasedItemId));
        assertThat(lectureOrderRepository.findViewableItem(SELLER_ID, LECTURE_ID)).isEmpty();
        assertThat(lectureOrderRepository.findViewableItem(BUYER_ID, 99L)).isEmpty();
    }

    @Test
    void 시청_가능한_항목을_최근_구매순으로_조회한다() {
        lectureOrderRepository.save(order(20L, NOW));
        lectureOrderRepository.save(order(30L, NOW));
        lectureOrderRepository.save(order(40L, NOW.plusDays(1)));
        LectureOrder canceled = lectureOrderRepository.save(order(50L, NOW.plusDays(2)));
        LectureOrderFixture.cancel(canceled, canceled.getItems().getFirst().getId(), NOW.plusDays(2));
        lectureOrderRepository.save(LectureOrderFixture.order(OTHER_BUYER_ID, List.of(withId(onSaleLecture(), 60L)),
                NOW.plusDays(3)));
        flushAndClear();

        Page<OrderItem> page = lectureOrderRepository.findViewableItems(BUYER_ID, PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(OrderItem::getLectureId).containsExactly(40L, 30L);
        assertThat(lectureOrderRepository.findViewableItems(BUYER_ID, PageRequest.of(1, 2)).getContent())
                .extracting(OrderItem::getLectureId).containsExactly(20L);
    }

    @Test
    void 구매자가_보유한_강의_ID만_찾는다() {
        lectureOrderRepository.save(order(20L, NOW));
        LectureOrder confirmed = lectureOrderRepository.save(order(30L, NOW));
        confirmed.confirmItem(confirmed.getItems().getFirst().getId(), NOW);
        LectureOrder canceled = lectureOrderRepository.save(order(40L, NOW));
        LectureOrderFixture.cancel(canceled, canceled.getItems().getFirst().getId(), NOW);
        lectureOrderRepository.save(LectureOrderFixture.order(OTHER_BUYER_ID, List.of(withId(onSaleLecture(), 50L)),
                NOW));
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
        LectureOrderFixture.cancel(canceled, itemId(canceled), oct1);
        flushAndClear();

        List<Long> dueItemIds = lectureOrderRepository.findAutoConfirmDueItemIds(now);

        // 기한이 now와 같은 10-02 구매분까지 대상이고, 10-03 구매분(10-11 00:00)과 확정·취소 항목은 빠진다.
        // 기한이 같으면 ID 순이다
        assertThat(dueItemIds).containsExactly(earlierDue, laterDue, sameDayLaterDue);
    }

    @Test
    void 지급_시각이_지나고_이의_제기가_없거나_기각된_구매_확정_항목_ID만_지급_예정_시각_순으로_조회한다() {
        // 구매일 + 31일 00:00이 지급 예정 시각이다. 10-01 구매분은 11-01, 10-02 구매분은 11-02 00:00
        LocalDateTime oct1 = LocalDateTime.of(2026, 10, 1, 10, 0);
        LocalDateTime now = LocalDateTime.of(2026, 11, 2, 0, 0);
        Long laterDue = itemId(saveConfirmed(20L, LocalDateTime.of(2026, 10, 2, 23, 0)));
        Long earlierDue = itemId(saveConfirmed(30L, oct1));
        Long sameDueLaterId = itemId(saveConfirmed(40L, oct1.plusHours(1)));
        Long rejected = itemId(withObjection(saveConfirmed(50L, oct1), ObjectionStatus.REJECTED));
        withObjection(saveConfirmed(60L, oct1), ObjectionStatus.FILED);
        withObjection(saveConfirmed(70L, oct1), ObjectionStatus.ACCEPTED);
        saveConfirmed(80L, LocalDateTime.of(2026, 10, 3, 0, 0));
        save(order(90L, oct1));
        LectureOrder paidOut = saveConfirmed(100L, oct1);
        LectureOrderFixture.payOut(paidOut, itemId(paidOut), now);
        flushAndClear();

        List<Long> dueItemIds = lectureOrderRepository.findPayoutDueItemIds(now);

        // 지급 예정 시각이 now와 같은 10-02 구매분까지 대상이다. 10-03 구매분(11-03 00:00), 구매 확정 전 항목,
        // 이의 제기 접수·인정 항목, 이미 지급한 항목은 빠진다. 지급 예정 시각이 같으면 ID 순이다
        assertThat(dueItemIds).containsExactly(earlierDue, sameDueLaterId, rejected, laterDue);
    }

    @Test
    void 지급_처리_중인_항목은_지급_대상_조회에서_빠진다() {
        LocalDateTime oct1 = LocalDateTime.of(2026, 10, 1, 10, 0);
        LocalDateTime now = LocalDateTime.of(2026, 11, 2, 0, 0);
        LectureOrder started = saveConfirmed(20L, oct1);
        started.startPayoutItem(itemId(started), now);
        LectureOrder due = saveConfirmed(30L, oct1);
        flushAndClear();

        // 01:00 배치는 선점한 항목을 다시 고르지 않는다. 실행·재시도는 HP 작업 배치가 한다 (D59 (5))
        assertThat(lectureOrderRepository.findPayoutDueItemIds(now)).containsExactly(itemId(due));
    }

    @Test
    void 반환_지급_처리_중인_강의를_다시_저장하면_유일_인덱스에_걸린다() {
        LectureOrder cancelPending = lectureOrderRepository.save(order());
        flushAndClear();
        cancelPending = lectureOrderRepository.findById(cancelPending.getId()).orElseThrow();
        cancelPending.requestCancelItem(itemId(cancelPending), NOW);
        flushAndClear();

        assertThatThrownBy(() -> lectureOrderRepository.saveAndFlush(order()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .cause().isInstanceOf(ConstraintViolationException.class)
                .extracting("constraintName").isEqualTo("uk_order_item_buyer_lecture_owned");
    }

    @Test
    void 부분_유일_인덱스_조건은_보유_상태와_같다() {
        String indexDefinition = (String) entityManager.createNativeQuery("select indexdef from pg_indexes "
                        + "where schemaname = 'lectures' and indexname = 'uk_order_item_buyer_lecture_owned'")
                .getSingleResult();

        // 보유 판정(OrderItemStatus.isHeld)과 인덱스가 어긋나면 재구매 차단이 빠지거나 정상 재구매가 막힌다 (D5, D59 (2))
        for (OrderItemStatus status : OrderItemStatus.values()) {
            assertThat(indexDefinition.contains("'" + status.name() + "'"))
                    .as(status.name()).isEqualTo(status.isHeld());
        }
    }

    @Test
    void 지급_처리_중은_시청_가능하고_구매_취소_처리_중은_시청할_수_없다() {
        LectureOrder payingOut = saveConfirmed(20L, LocalDateTime.of(2026, 9, 1, 10, 0));
        payingOut.startPayoutItem(itemId(payingOut), NOW);
        LectureOrder canceling = save(order(30L, NOW));
        canceling.requestCancelItem(itemId(canceling), NOW);
        flushAndClear();

        assertThat(lectureOrderRepository.findViewableItem(BUYER_ID, 20L)).isPresent();
        assertThat(lectureOrderRepository.findViewableItem(BUYER_ID, 30L)).isEmpty();
        assertThat(lectureOrderRepository.findHeldLectureIds(BUYER_ID, List.of(20L, 30L)))
                .containsExactlyInAnyOrder(20L, 30L);
    }

    @Test
    void 판매자의_주문_항목을_상태와_관계없이_최근_구매순으로_조회한다() {
        Long first = itemId(save(order(20L, NOW)));
        Long second = itemId(save(order(30L, NOW)));
        LectureOrder canceled = save(order(40L, NOW.plusDays(1)));
        LectureOrderFixture.cancel(canceled, itemId(canceled), NOW.plusDays(1));
        Lecture otherSellers = withId(onSaleLecture(), 50L);
        ReflectionTestUtils.setField(otherSellers, "sellerUserId", OTHER_BUYER_ID);
        save(LectureOrderFixture.order(BUYER_ID, List.of(otherSellers), NOW.plusDays(2)));
        flushAndClear();

        Page<OrderItem> page = lectureOrderRepository.findSales(SELLER_ID, PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(OrderItem::getId).containsExactly(itemId(canceled), second);
        assertThat(lectureOrderRepository.findSales(SELLER_ID, PageRequest.of(1, 2)).getContent())
                .extracting(OrderItem::getId).containsExactly(first);
    }

    @Test
    void 주문_항목_ID로_주문을_찾는다() {
        LectureOrder saved = lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID,
                List.of(withId(onSaleLecture(), 20L), withId(onSaleLecture(), 30L)), NOW));
        Long secondItemId = saved.getItems().get(1).getId();
        flushAndClear();

        assertThat(lectureOrderRepository.findByItemId(secondItemId))
                .hasValueSatisfying(order -> assertThat(order.getId()).isEqualTo(saved.getId()));
        assertThat(lectureOrderRepository.findByItemId(99_999L)).isEmpty();
    }

    private LectureOrder save(LectureOrder order) {
        return lectureOrderRepository.save(order);
    }

    /** 구매 시각에 바로 구매 확정한 주문 */
    private LectureOrder saveConfirmed(Long lectureId, LocalDateTime purchasedAt) {
        LectureOrder order = save(order(lectureId, purchasedAt));
        order.confirmItem(itemId(order), purchasedAt);
        return order;
    }

    // 이의 제기 접수·판단(#156)이 아직 없어 상태만 넣는다
    private static LectureOrder withObjection(LectureOrder order, ObjectionStatus objectionStatus) {
        ReflectionTestUtils.setField(order.getItems().getFirst(), "objectionStatus", objectionStatus);
        return order;
    }

    private static Long itemId(LectureOrder order) {
        return order.getItems().getFirst().getId();
    }

    private static LectureOrder order() {
        return order(LECTURE_ID, NOW);
    }

    private static LectureOrder order(Long lectureId, LocalDateTime purchasedAt) {
        return LectureOrderFixture.order(BUYER_ID, List.of(withId(onSaleLecture(), lectureId)), purchasedAt);
    }

    private static LectureOrder pending(Long lectureId) {
        return LectureOrderFixture.pending(BUYER_ID, List.of(withId(onSaleLecture(), lectureId)), NOW);
    }

    private long countRows(String sql) {
        return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
