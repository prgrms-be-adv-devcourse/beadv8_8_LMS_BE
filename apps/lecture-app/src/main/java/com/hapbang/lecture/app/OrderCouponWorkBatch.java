package com.hapbang.lecture.app;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.lecture.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

/**
 * 쿠폰 예약·해제 작업 재전송 배치. 기본 1분마다(매분 30초, 측정·합의 전 값) 실행한다. 처리는 {@link OrderCouponWorkUseCase}가 한다
 */
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class OrderCouponWorkBatch {

    private final OrderCouponWorkUseCase orderCouponWorkUseCase;

    @Scheduled(cron = "${lecture.order.coupon-work-cron:30 * * * * *}", zone = "Asia/Seoul")
    void run() {
        orderCouponWorkUseCase.runDue();
    }
}
