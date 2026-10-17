package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.config.AppearanceBatchProperties;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.HpTaskStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * HP 작업 재시도 (배치). 다음 시도 시각이 지난 대기 작업을 다시 실행한다.
 * <ul>
 *     <li>처음 시도 전인 작업은 만든 지 대기 시간({@code pendingGrace})이 지난 것만 가져간다.
 *     거절·취소 직후 요청 안에서 바로 실행 중인 작업과 겹치지 않도록 하기 위해서다.</li>
 *     <li>한 번에 최대 {@code batchSize}건. 작업마다 따로 실행하고, 한 작업의 예외가 다른 작업을 막지 않는다.</li>
 *     <li>끝나면 대기·격리 작업 수 지표를 갱신한다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HpTaskRetryUseCase {

    private final HpTaskSupport hpTaskSupport;
    private final HpTaskExecuteUseCase hpTaskExecuteUseCase;
    private final HpTaskMetrics hpTaskMetrics;
    private final AppearanceBatchProperties batchProperties;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @return 실행한 작업 수
     */
    public int retryDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> taskIds = hpTaskSupport.findRunnableIds(now, now.minus(batchProperties.pendingGrace()),
                batchProperties.batchSize());
        for (Long taskId : taskIds) {
            try {
                hpTaskExecuteUseCase.execute(taskId);
            } catch (RuntimeException exception) {
                log.warn("HP 작업 재시도 중 예외, 다음 작업으로 넘어간다 taskId={}", taskId, exception);
            }
        }
        hpTaskMetrics.updateBacklog(hpTaskSupport.countByStatus(HpTaskStatus.PENDING),
                hpTaskSupport.countByStatus(HpTaskStatus.DEAD));
        if (!taskIds.isEmpty()) {
            log.info("HP 작업 재시도 count={}", taskIds.size());
        }
        return taskIds.size();
    }
}
