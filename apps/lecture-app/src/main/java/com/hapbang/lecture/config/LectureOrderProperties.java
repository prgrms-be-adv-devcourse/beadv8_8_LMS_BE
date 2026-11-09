package com.hapbang.lecture.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 강의 주문 설정 ({@code lecture.order.*}). 값을 주지 않으면 기본값이다.
 *
 * @param holdRequestBudget    주문 요청 안에서 보류·보상 반환을 시도하는 시간 예산. 넘으면 남은 작업은 배치에 넘긴다(D68). 기본 10초
 * @param couponWorkRetryAfter   쿠폰 예약·해제 작업을 보낸 뒤 결과를 모를 때 같은 내용으로 다시 보내기까지 기다리는 시간.
 *                               기본 30초(측정·합의 전 값)
 * @param couponWorkMaxAttempts  쿠폰 예약·해제 작업을 보내는 최대 횟수(주문 요청의 첫 시도 포함). 닿으면 {@code DEAD}로 멈추고
 *                               운영자가 확인한다. 기본 10(측정·합의 전 값)
 * @param couponWorkBatchSize    쿠폰 작업 재전송 배치가 한 번에 보낼 작업 수. 기본 100(측정·합의 전 값)
 * <p>
 * 쿠폰 작업 재전송 배치 주기는 {@code lecture.order.coupon-work-cron}(기본 1분마다, 측정·합의 전 값)으로 {@code OrderCouponWorkBatch}가 읽는다.
 */
@ConfigurationProperties("lecture.order")
public record LectureOrderProperties(Duration holdRequestBudget, Duration couponWorkRetryAfter,
                                     Integer couponWorkMaxAttempts, Integer couponWorkBatchSize) {

    public LectureOrderProperties {
        holdRequestBudget = holdRequestBudget == null ? Duration.ofSeconds(10) : holdRequestBudget;
        couponWorkRetryAfter = couponWorkRetryAfter == null ? Duration.ofSeconds(30) : couponWorkRetryAfter;
        couponWorkMaxAttempts = couponWorkMaxAttempts == null ? 10 : couponWorkMaxAttempts;
        couponWorkBatchSize = couponWorkBatchSize == null ? 100 : couponWorkBatchSize;
    }
}
