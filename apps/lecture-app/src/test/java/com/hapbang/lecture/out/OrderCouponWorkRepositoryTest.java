package com.hapbang.lecture.out;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.OrderCouponWork;
import com.hapbang.lecture.domain.OrderCouponWorkKind;
import com.hapbang.lecture.domain.OrderCouponWorkStatus;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

// 주문 쿠폰 작업 저장과 재시도 배치 대상 조회
@RepositoryTest
class OrderCouponWorkRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 10, 0);
    private static final Duration RETRY_AFTER = Duration.ofSeconds(30);

    @Autowired
    private OrderCouponWorkRepository orderCouponWorkRepository;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 재시도_대상은_다음_시도_시각이_지난_PENDING_작업이고_오래_기다린_것부터다() {
        OrderCouponWork later = pending(1L, NOW.minusSeconds(10));
        OrderCouponWork earlier = pending(2L, NOW.minusSeconds(40));
        OrderCouponWork dueNow = pending(3L, NOW);
        pending(4L, NOW.plusSeconds(1));
        OrderCouponWork reserved = pending(5L, NOW.minusSeconds(60));
        reserved.markReserved(NOW);
        OrderCouponWork dead = pending(6L, NOW.minusSeconds(60));
        dead.markDead(NOW);
        flushAndClear();

        assertThat(orderCouponWorkRepository.findDue(NOW, PageRequest.of(0, 10)))
                .extracting(OrderCouponWork::getId)
                .containsExactly(earlier.getId(), later.getId(), dueNow.getId());
        assertThat(orderCouponWorkRepository.findDue(NOW, PageRequest.of(0, 1)))
                .extracting(OrderCouponWork::getId).containsExactly(earlier.getId());
    }

    @Test
    void 주문의_종류별_작업을_찾고_같은_종류를_두_번_저장하면_유일_제약에_걸린다() {
        Long reserveId = orderCouponWorkRepository.saveAndFlush(OrderCouponWork.reserve(7L, NOW)).getId();
        orderCouponWorkRepository.saveAndFlush(OrderCouponWork.release(7L, NOW));
        flushAndClear();

        assertThat(orderCouponWorkRepository.findByOrderIdAndKind(7L, OrderCouponWorkKind.RESERVE))
                .hasValueSatisfying(work -> assertThat(work.getId()).isEqualTo(reserveId));
        assertThatThrownBy(() -> orderCouponWorkRepository.saveAndFlush(OrderCouponWork.reserve(7L, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .cause().isInstanceOf(ConstraintViolationException.class)
                .extracting("constraintName").isIn("uk_order_coupon_work_work_id", "uk_order_coupon_work_order_kind");
    }

    @Test
    void 관리자_목록은_주문_상태를_함께_최신순으로_주고_상태와_종류로_거른다() {
        LectureOrder first = lectureOrderRepository.save(LectureOrderFixture.pending(OTHER_USER_ID,
                List.of(withId(onSaleLecture(), 10L)), NOW));
        LectureOrder second = lectureOrderRepository.save(LectureOrderFixture.pending(OTHER_USER_ID,
                List.of(withId(onSaleLecture(), 20L)), NOW));
        second.markHoldFailed();
        OrderCouponWork deadReserve = OrderCouponWork.reserve(first.getId(), NOW);
        deadReserve.markDead(NOW);
        orderCouponWorkRepository.save(deadReserve);
        OrderCouponWork reserved = OrderCouponWork.reserve(second.getId(), NOW.plusMinutes(1));
        reserved.markReserved(NOW.plusMinutes(1));
        orderCouponWorkRepository.save(reserved);
        orderCouponWorkRepository.save(OrderCouponWork.release(second.getId(), NOW.plusMinutes(2)));
        flushAndClear();

        assertThat(orderCouponWorkRepository.search(null, null, PageRequest.of(0, 10)).getContent())
                .extracting(row -> row.work().getWorkId(), OrderCouponWorkRow::orderStatus)
                .containsExactly(
                        tuple("lecture-order:" + second.getId() + ":release", LectureOrderStatus.HOLD_FAILED),
                        tuple("lecture-order:" + second.getId() + ":reserve", LectureOrderStatus.HOLD_FAILED),
                        tuple("lecture-order:" + first.getId() + ":reserve", LectureOrderStatus.HOLD_PENDING));
        assertThat(orderCouponWorkRepository.search(OrderCouponWorkStatus.DEAD, OrderCouponWorkKind.RESERVE,
                PageRequest.of(0, 10))).extracting(row -> row.work().getOrderId()).containsExactly(first.getId());
        assertThat(orderCouponWorkRepository.search(null, OrderCouponWorkKind.RELEASE, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(1);
    }

    /** 시도를 한 번 기록해 다음 시도 시각이 {@code nextAttemptAt}인 작업 */
    private OrderCouponWork pending(Long orderId, LocalDateTime nextAttemptAt) {
        OrderCouponWork work = OrderCouponWork.reserve(orderId, nextAttemptAt.minus(RETRY_AFTER));
        work.startAttempt(nextAttemptAt.minus(RETRY_AFTER), RETRY_AFTER);
        return orderCouponWorkRepository.save(work);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
