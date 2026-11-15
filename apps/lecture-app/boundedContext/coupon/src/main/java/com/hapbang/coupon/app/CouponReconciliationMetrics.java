package com.hapbang.coupon.app;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.hapbang.coupon.domain.IssueReconciliationRule.Mismatch;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 발급 대사·적체 차단·용량 예약 지표(Micrometer). 강의 HP 대사 지표와 같은 모양이다.
 * <ul>
 *     <li>{@code coupon.reconciliation.mismatches{kind}}: 마지막 대사 회차에서 불일치로 확정해 접수를 보류한 쿠폰 수(종류별). 0보다 크면 확인이 필요하다</li>
 *     <li>{@code coupon.issue.backlog_holds}: 마지막 대사 회차 뒤 적체(BACKLOG)로 신규 접수를 보류 중인 쿠폰 수</li>
 *     <li>{@code coupon.capacity.reserved}: 발급 저장소 용량 예약 합계(Redis 기록이 남은 쿠폰의 수량 합). 예약·해제·대사 때 바꾼다</li>
 * </ul>
 * 레지스트리가 없는 테스트에서는 메모리 레지스트리를 쓴다.
 */
@Component
public class CouponReconciliationMetrics {

    static final String MISMATCHES = "coupon.reconciliation.mismatches";
    static final String BACKLOG_HOLDS = "coupon.issue.backlog_holds";
    static final String CAPACITY_RESERVED = "coupon.capacity.reserved";

    private final Map<Mismatch, AtomicLong> mismatches = new EnumMap<>(Mismatch.class);
    private final AtomicLong backlogHolds = new AtomicLong();
    private final AtomicLong capacityReserved = new AtomicLong();

    public CouponReconciliationMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
        for (Mismatch kind : Mismatch.values()) {
            AtomicLong count = new AtomicLong();
            mismatches.put(kind, count);
            Gauge.builder(MISMATCHES, count, AtomicLong::get)
                    .description("마지막 쿠폰 발급 대사에서 불일치로 접수를 보류한 쿠폰 수")
                    .tag("kind", kind.name())
                    .register(meterRegistry);
        }
        Gauge.builder(BACKLOG_HOLDS, backlogHolds, AtomicLong::get)
                .description("적체로 신규 접수를 보류 중인 쿠폰 수")
                .register(meterRegistry);
        Gauge.builder(CAPACITY_RESERVED, capacityReserved, AtomicLong::get)
                .description("쿠폰 발급 저장소 용량 예약 합계(장)")
                .register(meterRegistry);
    }

    /** 끝난 대사 회차의 종류별 불일치 쿠폰 수와 적체 보류 쿠폰 수로 바꾼다(이번에 없는 종류는 0) */
    public void updateRun(Map<Mismatch, Long> mismatchCounts, long backlogHoldCount) {
        mismatches.forEach((kind, count) -> count.set(mismatchCounts.getOrDefault(kind, 0L)));
        backlogHolds.set(backlogHoldCount);
    }

    public void updateCapacityReserved(long reservedTotal) {
        capacityReserved.set(reservedTotal);
    }

    /** 테스트·확인용 */
    public long mismatches(Mismatch kind) {
        return mismatches.get(kind).get();
    }

    /** 테스트·확인용 */
    public long backlogHolds() {
        return backlogHolds.get();
    }

    /** 테스트·확인용 */
    public long capacityReserved() {
        return capacityReserved.get();
    }
}
