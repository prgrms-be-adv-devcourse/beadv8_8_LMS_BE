package com.hapbang.coupon.app;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 주문 결제·취소 완료 소비 지표 (Micrometer).
 * <ul>
 *     <li>{@code coupon.order.consume{event=paid|cancel_completed, result=applied|fact_only|duplicate}}: 이벤트 처리 결과별 건수.
 *     fact_only는 늦은 결제 완료·해제된 시도의 취소 완료 등 상태를 바꾸지 않은 이벤트다</li>
 * </ul>
 * 소비 실패(재시도 포함)와 lag는 Spring Kafka·Kafka client 기본 지표로 본다(그룹 {@code lecture-app.coupon-order}). 레지스트리가 없는
 * 테스트에서는 메모리 레지스트리를 쓴다.
 */
@Component
public class CouponOrderConsumerMetrics {

    static final String CONSUME = "coupon.order.consume";

    private final MeterRegistry meterRegistry;

    public CouponOrderConsumerMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
    }

    void record(String event, CouponOrderConsumerUseCase.Result result) {
        meterRegistry.counter(CONSUME, "event", event, "result", result.metricTag()).increment();
    }
}
