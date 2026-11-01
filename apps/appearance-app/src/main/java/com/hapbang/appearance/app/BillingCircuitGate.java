package com.hapbang.appearance.app;

import java.util.concurrent.atomic.AtomicBoolean;

import com.hapbang.appearance.out.billing.BillingClient;

import lombok.extern.slf4j.Slf4j;

/**
 * 지갑을 부르는 배치(HP 작업 재시도·보류 재확인)가 한 건을 보내기 전마다 지갑 회로를 본다 (#439). 배치마다 하나씩 둔다.
 * <ul>
 *     <li>회로가 열려 있으면(OPEN) 보내지 않는다. 열린 회로에는 요청이 나가지 않고 HP 작업은 다음 시도 시각만 뒤로 미뤄진다
 *     (시도 횟수는 그대로, {@code HpTask.postpone}).</li>
 *     <li>회차 시작 때만 보지 않고 건마다 본다. 시험 중(HALF_OPEN)에는 회로가 정한 수만큼만 실제로 보내고,
 *     시험이 실패하면 회차 도중에 다시 열린다. 그 뒤 남은 건은 다음 회차로 넘긴다.</li>
 *     <li>로그는 상태가 바뀔 때만 남긴다(멈춤 WARN, 재개 INFO). 장애 동안 매분 같은 로그가 쌓이지 않도록.</li>
 * </ul>
 */
@Slf4j
final class BillingCircuitGate {

    private final String batchName;
    private final AtomicBoolean stopped = new AtomicBoolean();

    BillingCircuitGate(String batchName) {
        this.batchName = batchName;
    }

    /** 지금 지갑에 보내도 되는지 (회로가 열려 있지 않은지) */
    boolean allows(BillingClient billingClient) {
        boolean circuitOpen = billingClient.isCircuitOpen();
        if (circuitOpen && stopped.compareAndSet(false, true)) {
            log.warn("지갑 회로가 열려 {} 배치를 멈춘다. 회로가 시험 중으로 넘어가면 다시 실행한다", batchName);
        } else if (!circuitOpen && stopped.compareAndSet(true, false)) {
            log.info("지갑 회로가 열려 있지 않아 {} 배치를 다시 실행한다", batchName);
        }
        return !circuitOpen;
    }
}
