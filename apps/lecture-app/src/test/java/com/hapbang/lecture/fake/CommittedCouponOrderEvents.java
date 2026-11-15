package com.hapbang.lecture.fake;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.hapbang.shared.lecture.event.OrderItemCancelCompletedEvent;
import com.hapbang.shared.lecture.event.OrderItemPaidEvent;

/**
 * 테스트용: 커밋된 트랜잭션의 쿠폰 주문 이벤트(결제 완료·취소 완료)만 모은다. 이벤트가 결제·반환 기록과 같은 트랜잭션에 묶였는지
 * (롤백되면 없음) 확인할 때 쓴다. 컨텍스트가 테스트 사이에 재사용되므로 테스트마다 {@link #reset()}한다.
 */
@Component
public class CommittedCouponOrderEvents {

    private final List<OrderItemPaidEvent> paid = new CopyOnWriteArrayList<>();
    private final List<OrderItemCancelCompletedEvent> cancelCompleted = new CopyOnWriteArrayList<>();

    @TransactionalEventListener
    void on(OrderItemPaidEvent event) {
        paid.add(event);
    }

    @TransactionalEventListener
    void on(OrderItemCancelCompletedEvent event) {
        cancelCompleted.add(event);
    }

    public void reset() {
        paid.clear();
        cancelCompleted.clear();
    }

    public List<OrderItemPaidEvent> paid() {
        return List.copyOf(paid);
    }

    public List<OrderItemCancelCompletedEvent> cancelCompleted() {
        return List.copyOf(cancelCompleted);
    }
}
