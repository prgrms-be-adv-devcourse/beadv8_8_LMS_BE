package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskRetryPolicy;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.global.exception.BusinessException;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * HP 작업 실행. 대기 중인 작업 하나를 Billing에 요청하고 결과를 기록한다.
 * 거절·응답 전 취소·만료 직후 한 번, 그 뒤로는 HP 작업 재시도 배치가 부른다.
 * <ul>
 *     <li>작업을 기록한 트랜잭션이 커밋된 뒤에 부른다. Billing 호출은 트랜잭션 밖에서 하고, 결과 기록만 짧은 트랜잭션으로 한다.</li>
 *     <li>결과
 *     <ul>
 *         <li>성공 → {@code DONE}</li>
 *         <li>Billing 거절(업무 예외) → 바로 {@code DEAD}. 다시 보내도 결과가 같으므로 Billing 오류 코드·메시지를 그대로 기록한다</li>
 *         <li>결과를 알 수 없는 실패 → 재시도 정책에 따라 다음 시도 시각을 정하고, 최대 횟수에 닿으면 {@code DEAD}</li>
 *     </ul></li>
 *     <li>격리되면 {@code [HP_TASK_DEAD]} 오류 로그를 남긴다 (로그에서 이 문자열로 찾는다).</li>
 *     <li>실패해도 예외를 밖으로 던지지 않는다. 업무 상태(거절·취소·만료)는 이미 커밋됐다.</li>
 *     <li>같은 작업을 두 곳에서 동시에 실행해도 같은 반환 요청 ID라 Billing은 한 번만 처리한다.
 *     결과 기록은 대기 중일 때만 하고, 버전 충돌이면 먼저 기록한 쪽을 따른다.</li>
 *     <li>결과 기록은 같은 클래스 안 메서드 호출이라 {@code @Transactional}이 적용되지 않으므로 {@link TransactionTemplate}으로 연다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HpTaskExecuteUseCase {

    private final HpTaskSupport hpTaskSupport;
    private final BillingClient billingClient;
    private final HpTaskRetryPolicy hpTaskRetryPolicy;
    private final HpTaskMetrics hpTaskMetrics;
    private final TransactionTemplate transactionTemplate;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    public void execute(Long taskId) {
        HpTask task = hpTaskSupport.getById(taskId);
        if (!task.isRunnable(LocalDateTime.now(clock))) {
            return;
        }
        try {
            request(task);
        } catch (BusinessException rejected) {
            String error = rejected.getErrorCode().getCode() + ": " + rejected.getMessage();
            record(taskId, pending -> pending.markDead(error, LocalDateTime.now(clock)));
            return;
        } catch (RuntimeException unknown) {
            log.warn("HP 작업 실패, 재시도 정책에 따라 다시 실행한다 taskId={}, key={}", taskId, task.getIdempotencyKey(),
                    unknown);
            String error = unknown.getClass().getSimpleName() + ": " + unknown.getMessage();
            double random = ThreadLocalRandom.current().nextDouble();
            record(taskId, pending -> pending.recordFailure(error, hpTaskRetryPolicy, LocalDateTime.now(clock), random));
            return;
        }
        record(taskId, pending -> pending.markDone(LocalDateTime.now(clock)));
    }

    /**
     * 작업 종류에 맞는 Billing 요청을 보낸다. 값을 돌려주는 switch 식이라 작업 종류가 늘었는데 분기를 빠뜨리면 컴파일되지 않는다
     * (분기 없이 Billing을 부르지 않고 완료로 기록되는 일을 막는다).
     */
    private void request(HpTask task) {
        Object result = switch (task.getTaskType()) {
            case RELEASE -> billingClient.release(toReleaseCommand(task));
            case PENALTY -> billingClient.settlePenalty(toPenaltyCommand(task));
        };
        log.debug("HP 작업 Billing 처리 taskId={}, result={}", task.getId(), result);
    }

    /**
     * 위약금 정산 작업 → Billing 위약금 정산 요청 (#239). 멱등 키를 취소 ID로 쓰고, 보류에서 위약금은 게스트에게 지급하고
     * 나머지는 호스트에게 반환한다. 지급 원천은 출연(요청서 출처 {@code APPEARANCE_REQUEST}, 모집 출처 {@code RECRUIT_APPEARANCE})이다.
     */
    private PenaltyCommand toPenaltyCommand(HpTask task) {
        SettlementSourceType sourceType = task.getRefType() == HpTaskRefType.APPEARANCE_RECRUIT
                ? SettlementSourceType.RECRUIT_APPEARANCE : SettlementSourceType.APPEARANCE_REQUEST;
        return new PenaltyCommand(task.getMemberId(), BillingReferenceType.valueOf(task.getRefType().name()),
                task.getRefId(), task.getIdempotencyKey(), task.getReceiverId(), sourceType, task.getAppearanceId(),
                task.releaseAmountOfPenalty(), task.getPayoutAmount());
    }

    /** 반환 작업 → Billing 반환 요청. 작업의 멱등 키를 반환 요청 ID로 쓰고, 기록한 금액만큼 반환한다 */
    private ReleaseCommand toReleaseCommand(HpTask task) {
        return ReleaseCommand.partial(task.getMemberId(), BillingReferenceType.valueOf(task.getRefType().name()),
                task.getRefId(), task.getIdempotencyKey(), task.getAmount());
    }

    /** 대기 중인 작업에만 결과를 기록하고, 기록한 결과를 지표·로그로 남긴다 */
    private void record(Long taskId, Consumer<HpTask> change) {
        HpTask recorded;
        try {
            recorded = transactionTemplate.execute(status -> {
                HpTask task = hpTaskSupport.getById(taskId);
                if (!task.isPending()) {
                    return null;
                }
                change.accept(task);
                return task;
            });
        } catch (ObjectOptimisticLockingFailureException conflict) {
            log.info("HP 작업 결과를 다른 실행이 먼저 기록했다 taskId={}", taskId);
            return;
        }
        if (recorded == null) {
            return;
        }
        report(recorded);
    }

    private void report(HpTask task) {
        if (task.getStatus() == HpTaskStatus.DONE) {
            hpTaskMetrics.recordDone();
            return;
        }
        if (task.getStatus() == HpTaskStatus.DEAD) {
            hpTaskMetrics.recordDead();
            log.error("[HP_TASK_DEAD] taskId={}, type={}, ref={}-{}, memberId={}, amount={}, attempts={}, error={}",
                    task.getId(), task.getTaskType(), task.getRefType(), task.getRefId(), task.getMemberId(),
                    task.getAmount(), task.getAttemptCount(), task.getLastError());
            return;
        }
        hpTaskMetrics.recordRetry();
    }
}
