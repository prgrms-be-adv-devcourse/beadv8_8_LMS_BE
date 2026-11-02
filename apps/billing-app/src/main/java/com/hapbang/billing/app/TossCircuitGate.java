package com.hapbang.billing.app;

import java.util.concurrent.atomic.AtomicBoolean;

import com.hapbang.billing.out.TossPaymentsClient;

import lombok.extern.slf4j.Slf4j;

/**
 * TossPayments 회로가 열려 있으면 복구 배치가 호출하지 않게 한다 (#562, 출연 BillingCircuitGate(#439)와 같은 방식).
 * <ul>
 *     <li>회차 시작이 아니라 건마다 확인한다. HALF_OPEN에서는 회로의 시험 호출만 나가고, 회차 도중 시험이 실패해 다시 열리면
 *     남은 건은 다음 회차로 넘긴다.</li>
 *     <li>열린 동안 건너뛴 건은 상태·확인 횟수를 바꾸지 않는다(환불 결과 재확인 횟수 {@code result_check_count}가 늘지 않는다).</li>
 *     <li>멈출 때와 다시 이어질 때만 로그를 남긴다.</li>
 * </ul>
 */
@Slf4j
final class TossCircuitGate {

    private final String batchName;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    TossCircuitGate(String batchName) {
        this.batchName = batchName;
    }

    boolean allows(TossPaymentsClient tossPaymentsClient) {
        boolean open = tossPaymentsClient.isCircuitOpen();
        if (open && stopped.compareAndSet(false, true)) {
            log.warn("TossPayments 회로가 열려 있어 {}를 멈춘다. 남은 건은 회로가 닫힌 뒤 다시 확인한다", batchName);
        } else if (!open && stopped.compareAndSet(true, false)) {
            log.info("TossPayments 회로가 닫혀 {}를 다시 진행한다", batchName);
        }
        return !open;
    }
}
