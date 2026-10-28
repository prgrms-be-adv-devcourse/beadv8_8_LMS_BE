package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.config.AppearanceBatchProperties;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearancePolicy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 출연 자동 확인 (배치, #282). 확인 기간(방송 예정 일시 + 7일)까지 호스트가 응답하지 않은 출연을 확인하고 게스트에게 지급한다.
 * <ul>
 *     <li>출연마다 [트랜잭션] 출연 배타 잠금 → 아직 성립 상태이고 기간이 끝났는지 다시 확인 → {@code CONFIRMED}(자동 확인)
 *     + 지급 작업 기록 (커밋) → [트랜잭션 밖] 지급 1회 실행. 실패한 지급은 HP 작업 재시도 배치가 이어서 한다.</li>
 *     <li>그사이 호스트가 응답했으면 건너뛴다. 취소 요청 처리 중인 출연은 대상이 아니다 (자동 거절 배치가 성립으로 되돌린 뒤 대상이 된다).</li>
 *     <li>출연마다 예외를 잡아 다음 출연을 계속 처리한다 (최대 {@code batchSize}건, 나머지는 다음 주기).</li>
 * </ul>
 * 트랜잭션은 같은 클래스 안 처리라 {@link TransactionTemplate}으로 연다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppearanceAutoConfirmUseCase {

    private final AppearanceSupport appearanceSupport;
    private final AppearanceConfirmRecorder appearanceConfirmRecorder;
    private final HpTaskExecuteUseCase hpTaskExecuteUseCase;
    private final AppearancePolicy appearancePolicy;
    private final AppearanceBatchProperties batchProperties;
    private final TransactionTemplate transactionTemplate;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @return 자동 확인한 출연 수
     */
    public int confirmDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime scheduledCutoff = now.minus(appearancePolicy.confirmPeriod());
        List<Long> appearanceIds = appearanceSupport.findAutoConfirmDueIds(scheduledCutoff,
                batchProperties.batchSize());
        int confirmed = 0;
        for (Long appearanceId : appearanceIds) {
            try {
                Long payoutTaskId = transactionTemplate.execute(status -> confirm(appearanceId, now));
                if (payoutTaskId == null) {
                    continue;
                }
                confirmed++;
                hpTaskExecuteUseCase.execute(payoutTaskId);
            } catch (RuntimeException exception) {
                log.warn("출연 자동 확인 중 예외, 다음 출연으로 넘어간다 appearanceId={}", appearanceId, exception);
            }
        }
        if (confirmed > 0) {
            log.info("출연 자동 확인 count={}", confirmed);
        }
        return confirmed;
    }

    /** 자동 확인하고 지급 작업을 기록한다. 그사이 호스트가 응답했으면 null */
    private Long confirm(Long appearanceId, LocalDateTime now) {
        Appearance appearance = appearanceSupport.getForUpdate(appearanceId);
        if (!appearance.isAutoConfirmDue(appearancePolicy, now)) {
            return null;
        }
        appearance.confirmAutomatically(appearancePolicy, now);
        Long taskId = appearanceConfirmRecorder.recordPayout(appearance, now);
        appearanceSupport.flushOrConflict();
        return taskId;
    }
}
