package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.config.AppearanceBatchProperties;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.out.billing.BillingClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * HP 작업 재시도 (배치). 다음 시도 시각이 지난 대기 작업을 다시 실행한다.
 * <ul>
 *     <li>처음 시도 전인 작업은 만든 지 대기 시간({@code pendingGrace})이 지난 것만 가져간다.
 *     거절·취소 직후 요청 안에서 바로 실행 중인 작업과 겹치지 않도록 하기 위해서다.
 *     관리자가 다시 시도한 작업은 다시 시도한 지 대기 시간이 지난 것만 가져간다 (#397, 관리자 요청 안의 실행과 겹치지 않도록).</li>
 *     <li>한 번에 최대 {@code batchSize}건. 작업마다 따로 실행하고, 한 작업의 예외가 다른 작업을 막지 않는다.</li>
 *     <li>지갑 회로가 열려 있으면 작업을 가져오지 않고, 회차 도중에 열리면 남은 작업을 실행하지 않는다 (#439, {@link BillingCircuitGate}).
 *     열린 회로에 보내면 요청은 나가지 않고 시도 횟수만 올라, 지갑이 오래 내려가 있으면 정상 작업이 {@code DEAD}가 된다.
 *     회로는 호출이 없어도 열린 시간이 지나면 시험 중(HALF_OPEN)으로 넘어가고, 그때부터 다시 실행한다.</li>
 *     <li>끝나면(건너뛴 회차도) 대기·격리 작업 수 지표를 갱신한다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HpTaskRetryUseCase {

    private final HpTaskSupport hpTaskSupport;
    private final HpTaskExecuteUseCase hpTaskExecuteUseCase;
    private final HpTaskMetrics hpTaskMetrics;
    private final BillingClient billingClient;
    private final AppearanceBatchProperties batchProperties;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;
    private final BillingCircuitGate circuitGate = new BillingCircuitGate("HP 작업 재시도");

    /**
     * @return 실행한 작업 수
     */
    public int retryDue() {
        int executed = circuitGate.allows(billingClient) ? runDue() : 0;
        updateBacklog();
        if (executed > 0) {
            log.info("HP 작업 재시도 count={}", executed);
        }
        return executed;
    }

    private int runDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> taskIds = hpTaskSupport.findRunnableIds(now, now.minus(batchProperties.pendingGrace()),
                batchProperties.batchSize());
        int executed = 0;
        for (Long taskId : taskIds) {
            // 시험 중에 보낸 작업이 실패하면 회로가 다시 열린다. 남은 작업은 다음 회차로 넘긴다
            if (!circuitGate.allows(billingClient)) {
                break;
            }
            try {
                hpTaskExecuteUseCase.execute(taskId);
            } catch (RuntimeException exception) {
                log.warn("HP 작업 재시도 중 예외, 다음 작업으로 넘어간다 taskId={}", taskId, exception);
            }
            executed++;
        }
        return executed;
    }

    private void updateBacklog() {
        hpTaskMetrics.updateBacklog(hpTaskSupport.countByStatus(HpTaskStatus.PENDING),
                hpTaskSupport.countByStatus(HpTaskStatus.DEAD));
    }
}
