package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureOrder;

/** 재생 동의(첫 재생 구매 확정, D7·D38). HP는 움직이지 않는다(지급은 구매일 D+30 배치) */
@Service
public class OrderPlayConsentUseCase {

    private final LectureOrderSupport lectureOrderSupport;
    private final Clock clock;

    public OrderPlayConsentUseCase(LectureOrderSupport lectureOrderSupport,
                                   @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureOrderSupport = lectureOrderSupport;
        this.clock = clock;
    }

    public OrderItemResponse consentToPlay(Long buyerUserId, Long orderId, Long orderItemId) {
        LectureOrder order = lectureOrderSupport.findOrder(buyerUserId, orderId);
        return OrderItemResponse.of(order.consentToPlay(orderItemId, LocalDateTime.now(clock)));
    }
}
