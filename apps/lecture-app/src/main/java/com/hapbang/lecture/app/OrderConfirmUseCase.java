package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;

import lombok.extern.slf4j.Slf4j;

/** 직접 구매 확정. HP는 움직이지 않는다(지급은 구매일 D+30 배치) */
@Slf4j
@Service
public class OrderConfirmUseCase {

    private final LectureOrderSupport lectureOrderSupport;
    private final LectureOrderEvents lectureOrderEvents;
    private final Clock clock;

    public OrderConfirmUseCase(LectureOrderSupport lectureOrderSupport, LectureOrderEvents lectureOrderEvents,
                               @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureOrderSupport = lectureOrderSupport;
        this.lectureOrderEvents = lectureOrderEvents;
        this.clock = clock;
    }

    public OrderItemResponse confirm(Long buyerUserId, Long orderId, Long orderItemId) {
        LectureOrder order = lectureOrderSupport.findOrder(buyerUserId, orderId);
        OrderItem item = order.confirmItem(orderItemId, LocalDateTime.now(clock));
        lectureOrderEvents.itemConfirmed(item);
        AfterCommit.run(() -> {
            log.info("구매를 확정했다 orderId={}, orderItemId={}, buyerUserId={}", orderId, orderItemId, buyerUserId);
        });
        return OrderItemResponse.of(item);
    }
}
