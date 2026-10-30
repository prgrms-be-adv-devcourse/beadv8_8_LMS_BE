package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.OrderHoldTasks;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.out.CartRepository;
import com.hapbang.lecture.out.LectureHpTaskRepository;
import com.hapbang.lecture.out.LectureOrderRepository;

/**
 * 결제 작업 결과로 주문의 결제 단계를 정한다 (D58 (3)·(6)). 결제 처리기들이 작업 결과를 기록하는 트랜잭션 안에서 부른다.
 * <ul>
 *     <li>주문 행을 먼저 잠가(D46) 같은 주문의 결과 기록을 한 줄로 세운다. 그래서 마지막으로 기록하는 쪽이 모든 작업 결과를 본다.</li>
 *     <li>모든 보류가 성공하면 결제 완료로 기록하고, 주문한 강의만 장바구니에서 뺀다(멱등).</li>
 *     <li>결제 취소 처리 중이면 성공한 보류마다 반환 작업을 만들고, 묶여 남은 HP가 없음이 확인되면 결제 실패다.</li>
 * </ul>
 * 반환 작업의 실행은 주문 요청(트랜잭션 밖)이나 HP 작업 재시도 배치가 한다.
 */
@Component
public class OrderHoldSettlement {

    static final String COMPENSATION_REQUEST_ID_PREFIX = "LECTURE-HOLD-COMPENSATE-";

    private final LectureOrderRepository lectureOrderRepository;
    private final LectureHpTaskRepository lectureHpTaskRepository;
    private final LectureHpTaskSupport lectureHpTaskSupport;
    private final CartRepository cartRepository;
    private final Clock clock;

    public OrderHoldSettlement(LectureOrderRepository lectureOrderRepository,
                               LectureHpTaskRepository lectureHpTaskRepository,
                               LectureHpTaskSupport lectureHpTaskSupport, CartRepository cartRepository,
                               @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureOrderRepository = lectureOrderRepository;
        this.lectureHpTaskRepository = lectureHpTaskRepository;
        this.lectureHpTaskSupport = lectureHpTaskSupport;
        this.cartRepository = cartRepository;
        this.clock = clock;
    }

    /** 보류가 성공했거나 보상 반환이 끝났다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settle(LectureHpTask task) {
        settle(lockOrder(task), LocalDateTime.now(clock));
    }

    /**
     * 보류가 확정 거절됐다. 결제 취소 처리 중으로 바꾸고, 한 번도 실행하지 않은 보류는 닫는다.
     * 이미 시도한 보류는 끝까지 확인한다 (D69 (5))
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reject(LectureHpTask task) {
        LectureOrder order = lockOrder(task);
        LocalDateTime now = LocalDateTime.now(clock);
        if (order.getStatus() == LectureOrderStatus.HOLD_PENDING) {
            order.startHoldCompensation();
            lectureHpTaskRepository.skipUnstartedHolds(order.getId(), now);
        }
        settle(order, now);
    }

    private void settle(LectureOrder order, LocalDateTime now) {
        OrderHoldTasks tasks = OrderHoldTasks.of(lectureHpTaskRepository.findByOrderIdIn(List.of(order.getId())));
        switch (order.getStatus()) {
            case HOLD_PENDING -> {
                if (tasks.allHoldsSucceeded()) {
                    order.markPaid(now);
                    removeOrderedLecturesFromCart(order, now);
                }
            }
            case HOLD_COMPENSATING -> {
                tasks.holdsToCompensate().forEach(hold -> registerCompensation(hold, now));
                if (tasks.isFailureSettled()) {
                    order.markHoldFailed();
                }
            }
            // 결제 단계가 끝난 뒤 다시 기록된 결과(관리자 재시도 등)는 바꿀 것이 없다
            case HOLD_FAILED, PAID, CANCELED -> {
            }
        }
    }

    private void registerCompensation(LectureHpTask hold, LocalDateTime now) {
        lectureHpTaskSupport.register(LectureHpTask.create(LectureHpTaskPurpose.HOLD_COMPENSATION,
                COMPENSATION_REQUEST_ID_PREFIX + hold.getOrderItemId(), hold.getOrderId(), hold.getOrderItemId(),
                hold.getPayerUserId(), null, hold.getAmountHp(), now));
    }

    private void removeOrderedLecturesFromCart(LectureOrder order, LocalDateTime now) {
        cartRepository.findByUserId(order.getBuyerUserId()).ifPresent(cart -> order.getItems().stream()
                .map(OrderItem::getLectureId)
                .forEach(lectureId -> cart.remove(lectureId, now)));
    }

    private LectureOrder lockOrder(LectureHpTask task) {
        return lectureOrderRepository.findForUpdateById(task.getOrderId())
                .orElseThrow(() -> new IllegalStateException("결제 작업의 주문이 없습니다. taskId=" + task.getId()
                        + ", orderId=" + task.getOrderId()));
    }
}
