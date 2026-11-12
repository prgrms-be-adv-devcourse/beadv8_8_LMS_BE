package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;

import lombok.extern.slf4j.Slf4j;

/** 재생 동의(첫 재생 구매 확정, D7·D38). HP는 움직이지 않는다(지급은 구매일 D+30 배치) */
@Slf4j
@Service
public class OrderPlayConsentUseCase {

    private final LectureOrderSupport lectureOrderSupport;
    private final LectureOrderEvents lectureOrderEvents;
    private final Clock clock;

    public OrderPlayConsentUseCase(LectureOrderSupport lectureOrderSupport, LectureOrderEvents lectureOrderEvents,
                                   @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureOrderSupport = lectureOrderSupport;
        this.lectureOrderEvents = lectureOrderEvents;
        this.clock = clock;
    }

    public OrderItemResponse consentToPlay(Long buyerUserId, Long orderId, Long orderItemId) {
        LectureOrder order = lectureOrderSupport.findOrder(buyerUserId, orderId);
        boolean purchased = order.getItems().stream()
                .anyMatch(item -> item.getId().equals(orderItemId) && item.getStatus() == OrderItemStatus.PURCHASED);
        OrderItem item = order.consentToPlay(orderItemId, LocalDateTime.now(clock));
        // 이미 확정·지급된 항목의 재생 동의는 아무것도 바꾸지 않으므로 남기지 않고 확정 이벤트도 다시 내지 않는다 (D38)
        if (purchased && item.getStatus() == OrderItemStatus.CONFIRMED) {
            lectureOrderEvents.itemConfirmed(item);
            AfterCommit.run(() -> {
                log.info("재생 동의로 구매를 확정했다 orderId={}, orderItemId={}, buyerUserId={}, confirmationType={}", orderId,
                        orderItemId, buyerUserId, item.getConfirmationType());
            });
        }
        return OrderItemResponse.of(item);
    }
}
