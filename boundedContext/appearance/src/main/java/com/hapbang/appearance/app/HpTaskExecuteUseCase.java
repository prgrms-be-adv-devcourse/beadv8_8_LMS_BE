package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.out.billing.BillingClient.Reason;
import com.hapbang.appearance.out.billing.BillingClient.RefType;
import com.hapbang.appearance.out.billing.BillingClient.ReleaseCommand;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * HP 작업 실행. 대기 중인 작업 하나를 Billing에 요청하고 결과를 기록한다.
 * <ul>
 *     <li>작업을 기록한 트랜잭션이 커밋된 뒤에 부른다. Billing 호출은 트랜잭션 밖에서 하고, 결과 기록만 짧은 트랜잭션으로 한다.</li>
 *     <li>실패해도 예외를 밖으로 던지지 않는다. 업무 상태(거절·취소)는 이미 커밋됐으므로 사용자 요청은 성공으로 끝내고,
 *     작업에 실패를 기록해 다시 실행되게 한다 (재시도·격리는 재시도 스케줄러에서 추가).</li>
 *     <li>결과 기록은 같은 클래스 안 메서드 호출이라 {@code @Transactional}이 적용되지 않으므로 {@link TransactionTemplate}으로 연다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HpTaskExecuteUseCase {

    /** 실패 후 다음 시도까지 기본 간격. 간격을 늘리는 정책은 재시도 스케줄러에서 정한다 */
    static final Duration FIRST_RETRY_DELAY = Duration.ofMinutes(1);

    private final HpTaskSupport hpTaskSupport;
    private final BillingClient billingClient;
    private final TransactionTemplate transactionTemplate;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    public void execute(Long taskId) {
        HpTask task = hpTaskSupport.getById(taskId);
        if (!task.isRunnable(LocalDateTime.now(clock))) {
            return;
        }
        try {
            billingClient.release(toReleaseCommand(task));
        } catch (RuntimeException exception) {
            log.warn("HP 작업 실패, 다시 실행하도록 남긴다 taskId={}, key={}", taskId, task.getIdempotencyKey(), exception);
            recordFailure(taskId, exception);
            return;
        }
        recordDone(taskId);
    }

    private ReleaseCommand toReleaseCommand(HpTask task) {
        return new ReleaseCommand(task.getIdempotencyKey(), RefType.valueOf(task.getRefType().name()),
                task.getRefId(), task.getMemberId(), task.getAmount(), Reason.valueOf(task.getReason().name()));
    }

    private void recordDone(Long taskId) {
        transactionTemplate.executeWithoutResult(status -> {
            HpTask task = hpTaskSupport.getById(taskId);
            task.markDone(LocalDateTime.now(clock));
        });
    }

    private void recordFailure(Long taskId, RuntimeException exception) {
        transactionTemplate.executeWithoutResult(status -> {
            HpTask task = hpTaskSupport.getById(taskId);
            LocalDateTime now = LocalDateTime.now(clock);
            task.markFailed(exception.getClass().getSimpleName() + ": " + exception.getMessage(),
                    now.plus(FIRST_RETRY_DELAY));
        });
    }
}
