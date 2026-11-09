package com.hapbang.coupon.app;

import java.util.Locale;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.hapbang.coupon.domain.IssueCompensation;
import com.hapbang.coupon.out.CouponDltLogRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * DLT·영구 거절 보상 지표 (Micrometer).
 * <ul>
 *     <li>{@code coupon.dlt.pending}: 아직 처리되지 않은 DLT 격리 기록 수(PENDING + 재발행 선점 중인 REPLAYING). 수집할 때마다 DB에서
 *     센다(세지 못하면 NaN). 0보다 크면 확인한다(복구는 그 쿠폰의 미처리가 0이어야 재구축으로 넘어간다)</li>
 *     <li>{@code coupon.dlt.isolated}: DLT로 격리한 발급 요청 수</li>
 *     <li>{@code coupon.issue.compensations{result=restored|already_compensated|marked_only|no_record|member_mismatch}}:
 *     영구 거절 보상 결과별 횟수. no_record·member_mismatch는 사람이 확인한다</li>
 * </ul>
 * 레지스트리가 없는 테스트에서는 메모리 레지스트리를 쓴다.
 */
@Slf4j
@Component
public class CouponDltMetrics {

    static final String PENDING = "coupon.dlt.pending";
    static final String ISOLATED = "coupon.dlt.isolated";
    static final String COMPENSATIONS = "coupon.issue.compensations";

    private final MeterRegistry meterRegistry;
    private final CouponDltLogRepository couponDltLogRepository;

    public CouponDltMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider,
                            CouponDltLogRepository couponDltLogRepository) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
        this.couponDltLogRepository = couponDltLogRepository;
        Gauge.builder(PENDING, this, CouponDltMetrics::countPending)
                .description("처리되지 않은 쿠폰 발급 DLT 격리 기록 수(PENDING·REPLAYING, 수집 때마다 셈, 세지 못하면 NaN)")
                .register(meterRegistry);
    }

    void recordIsolated() {
        meterRegistry.counter(ISOLATED).increment();
    }

    void recordCompensation(IssueCompensation result) {
        meterRegistry.counter(COMPENSATIONS, "result", result.name().toLowerCase(Locale.ROOT)).increment();
    }

    double countPending() {
        try {
            return couponDltLogRepository.countUnresolved();
        } catch (RuntimeException e) {
            log.warn("쿠폰 발급 DLT 격리 기록 수를 세지 못했다", e);
            return Double.NaN;
        }
    }
}
