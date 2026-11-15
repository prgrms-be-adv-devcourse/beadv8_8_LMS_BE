package com.hapbang.coupon.app;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 발급 확정 소비 지표 (Micrometer).
 * <ul>
 *     <li>{@code coupon.issue.consume{result=issued|duplicate|rejected_over_quantity|rejected_member_already_issued}}: 처리 결과별 건수.
 *     거절 두 가지는 Redis 재고·회원 기록이 DB와 어긋났다는 신호라 0보다 크면 확인한다</li>
 * </ul>
 * 소비 실패(재시도 포함)와 lag는 Spring Kafka·Kafka client 기본 지표로 본다: listener observation 타이머 {@code spring.kafka.listener}
 * ({@code error} 태그), consumer lag {@code kafka.consumer.fetch.manager.records.lag.max}(그룹 {@code lecture-app.coupon-issue}의
 * client). 레지스트리가 없는 테스트에서는 메모리 레지스트리를 쓴다.
 */
@Component
public class CouponIssueConsumerMetrics {

    static final String CONSUME = "coupon.issue.consume";

    private final MeterRegistry meterRegistry;

    public CouponIssueConsumerMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
    }

    void record(CouponIssueConsumerUseCase.Result result) {
        meterRegistry.counter(CONSUME, "result", result.metricTag()).increment();
    }
}
