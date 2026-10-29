package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.config.LectureHpTaskProperties;
import com.hapbang.lecture.domain.LectureHpAttemptResult;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskAttempt;
import com.hapbang.lecture.domain.LectureHpTaskClassifier;
import com.hapbang.lecture.domain.LectureHpTaskOutcome;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureHpTaskRetryPolicy;
import com.hapbang.lecture.domain.LectureHpTaskStatus;
import com.hapbang.lecture.domain.LectureHpTaskType;
import com.hapbang.lecture.out.LectureHpTaskAttemptRepository;
import com.hapbang.lecture.out.LectureHpTaskRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;
import com.hapbang.shared.billing.out.BillingApiClient;
import com.hapbang.shared.billing.out.BillingCallException;
import com.hapbang.shared.billing.out.BillingCallResult;

import lombok.extern.slf4j.Slf4j;

/**
 * 강의 HP 작업 실행기 (D57, D62, #275). 작업 하나를 선점해 지갑 내부 API로 보내고 결과를 기록한다.
 * <ol>
 *     <li>선점(짧은 트랜잭션): 조건부 UPDATE로 {@code PROCESSING} + 선점 표식 + 시도 행. 선점이 만료된 앞 시도는 {@code LEASE_EXPIRED}로 닫는다.</li>
 *     <li>지갑 호출(트랜잭션 밖): {@link BillingApiClient}. 실패를 작업 유형별로 나눈다({@link LectureHpTaskClassifier}).</li>
 *     <li>결과 기록(짧은 트랜잭션): 선점 표식이 같을 때만 작업·시도 행을 바꾸고 목적별 {@link LectureHpTaskResultHandler}를 부른다.
 *     선점이 바뀐 뒤 늦게 온 결과는 버리고 경고만 남긴다.</li>
 * </ol>
 * 업무 요청 안의 즉시 실행과 재시도 배치가 같은 {@link #execute}를 쓴다. 강의 트랜잭션 안에서 부르면 실패한다.
 * 실행 중 예외(DB 오류 등)는 밖으로 던지지 않는다. 작업은 남아 선점이 만료된 뒤 같은 요청 ID로 다시 실행된다.
 */
@Slf4j
@Service
public class LectureHpTaskExecutor {

    static final String DEAD_LOG = "[LECTURE_HP_TASK_DEAD]";
    static final String RESULT_HANDLER_MISSING = "RESULT_HANDLER_MISSING";
    static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    static final String LEASE_EXPIRED = "LEASE_EXPIRED";

    private final LectureHpTaskRepository lectureHpTaskRepository;
    private final LectureHpTaskAttemptRepository lectureHpTaskAttemptRepository;
    private final BillingApiClient billingApiClient;
    private final Map<LectureHpTaskPurpose, LectureHpTaskResultHandler> handlers;
    private final LectureHpTaskProperties properties;
    private final LectureHpTaskRetryPolicy retryPolicy;
    private final LectureHpTaskMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public LectureHpTaskExecutor(LectureHpTaskRepository lectureHpTaskRepository,
                                 LectureHpTaskAttemptRepository lectureHpTaskAttemptRepository,
                                 BillingApiClient billingApiClient, List<LectureHpTaskResultHandler> handlers,
                                 LectureHpTaskProperties properties, LectureHpTaskRetryPolicy retryPolicy,
                                 LectureHpTaskMetrics metrics, PlatformTransactionManager transactionManager,
                                 @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureHpTaskRepository = lectureHpTaskRepository;
        this.lectureHpTaskAttemptRepository = lectureHpTaskAttemptRepository;
        this.billingApiClient = billingApiClient;
        this.handlers = byPurpose(handlers);
        this.properties = properties;
        this.retryPolicy = retryPolicy;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 작업 하나를 실행한다. 선점하지 못하면(다른 실행 중, 시각 전, 끝남) 아무것도 하지 않는다 */
    public void execute(Long taskId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("HP 작업은 강의 트랜잭션 밖에서 실행한다. taskId=" + taskId);
        }
        try {
            run(taskId);
        } catch (RuntimeException unexpected) {
            log.error("HP 작업 실행 중 예외, 선점이 만료된 뒤 다시 실행한다 taskId={}", taskId, unexpected);
        }
    }

    private void run(Long taskId) {
        Claim claim = transactionTemplate.execute(status -> claim(taskId));
        if (claim == null) {
            return;
        }
        if (!handlers.containsKey(claim.task().getPurpose())) {
            record(claim, new Attempt(LectureHpAttemptResult.INVARIANT_VIOLATION, RESULT_HANDLER_MISSING, null,
                    "결과 처리기가 없는 목적이라 지갑을 부르지 않았다. purpose=" + claim.task().getPurpose()));
            return;
        }
        record(claim, call(claim.task()));
    }

    /** 선점한다. 한도에 닿은 채 선점이 만료된 작업은 {@code DEAD}로 닫는다. 선점하지 못하면 {@code null} */
    private Claim claim(Long taskId) {
        LocalDateTime now = LocalDateTime.now(clock);
        UUID token = UUID.randomUUID();
        int maxAttempts = properties.retryMaxAttempts();
        if (lectureHpTaskRepository.claim(taskId, token, now, now.plus(properties.lease()), maxAttempts) == 0) {
            if (lectureHpTaskRepository.markLeaseExhaustedDead(taskId, now, maxAttempts, LEASE_EXPIRED) == 1) {
                lectureHpTaskAttemptRepository.closeOpenAttempts(taskId, now);
                LectureHpTask dead = lectureHpTaskRepository.findById(taskId).orElseThrow();
                notifyDead(dead);
                afterCommit(() -> report(dead));
            }
            return null;
        }
        lectureHpTaskAttemptRepository.closeOpenAttempts(taskId, now);
        LectureHpTask task = lectureHpTaskRepository.findById(taskId).orElseThrow();
        lectureHpTaskAttemptRepository.save(
                LectureHpTaskAttempt.start(taskId, task.getTotalAttemptCount(), token, now));
        return new Claim(task, token);
    }

    /** 지갑을 부른다. 트랜잭션 밖이다 */
    private Attempt call(LectureHpTask task) {
        try {
            BillingCallResult<?> result = switch (task.getTaskType()) {
                case HOLD -> billingApiClient.hold(new HoldCommand(task.getPayerUserId(),
                        BillingReferenceType.LECTURE_ORDER_ITEM, task.getOrderItemId(), task.getAmountHp()));
                case RELEASE -> billingApiClient.release(ReleaseCommand.full(task.getPayerUserId(),
                        BillingReferenceType.LECTURE_ORDER_ITEM, task.getOrderItemId(), task.getRequestId()));
                case PAYOUT -> billingApiClient.payout(PayoutCommand.full(task.getPayerUserId(),
                        BillingReferenceType.LECTURE_ORDER_ITEM, task.getOrderItemId(), task.getRequestId(),
                        task.getReceiverUserId(), SettlementSourceType.LECTURE_ORDER_ITEM, task.getOrderItemId()));
            };
            return switch (result) {
                case BillingCallResult.Succeeded<?> succeeded -> verifyAmount(task, succeeded.value());
                case BillingCallResult.Failed<?> failed -> new Attempt(
                        LectureHpTaskClassifier.classify(task.getTaskType(), failed.code()), failed.code().name(),
                        failed.rawCode(), failed.httpStatus() + " " + failed.detail());
            };
        } catch (BillingCallException e) {
            log.warn("HP 작업 지갑 응답 없음 taskId={}, requestId={}, kind={}, status={}", task.getId(),
                    task.getRequestId(), e.getKind(), e.getHttpStatus());
            return new Attempt(LectureHpTaskClassifier.classify(e.getKind()), e.getKind().name(), e.getRawCode(),
                    describe(e));
        } catch (RuntimeException e) {
            // 그 밖의 예외는 지갑이 처리했는지 알 수 없다 (D57 (5))
            log.warn("HP 작업 지갑 호출 중 예외 taskId={}, requestId={}", task.getId(), task.getRequestId(), e);
            return new Attempt(LectureHpAttemptResult.UNKNOWN_RESULT, LectureHpAttemptResult.UNKNOWN_RESULT.name(),
                    null, describe(e));
        }
    }

    /** 결과 금액이 항목 가격과 다르면 불변식 위반이다 (D43). 같은 요청의 재현(replayed)은 정상이다 */
    private static Attempt verifyAmount(LectureHpTask task, Object value) {
        long amount = switch (value) {
            case HoldResultDto hold -> hold.heldHp();
            case ReleaseResultDto release -> release.releasedHp();
            case PayoutResultDto payout -> payout.paidHp();
            default -> throw new IllegalStateException("알 수 없는 지갑 결과입니다. " + value);
        };
        if (amount != task.getAmountHp()) {
            return new Attempt(LectureHpAttemptResult.INVARIANT_VIOLATION, AMOUNT_MISMATCH, null,
                    "결과 금액 " + amount + " ≠ 작업 금액 " + task.getAmountHp() + ": " + value);
        }
        return new Attempt(LectureHpAttemptResult.SUCCEEDED, null, null, null);
    }

    /** 결과를 기록한다. 선점 표식이 바뀌었으면(늦은 결과) 버린다 */
    private void record(Claim claim, Attempt attempt) {
        Long taskId = claim.task().getId();
        try {
            LectureHpTask recorded = transactionTemplate.execute(status -> {
                LectureHpTask task = lectureHpTaskRepository.findById(taskId).orElseThrow();
                if (!task.isClaimedBy(claim.token())) {
                    log.warn("late result ignored: 선점이 바뀐 뒤 온 HP 작업 결과를 버린다 taskId={}, result={}", taskId,
                            attempt.result());
                    return null;
                }
                LocalDateTime now = LocalDateTime.now(clock);
                lectureHpTaskAttemptRepository.findByTaskIdAndClaimToken(taskId, claim.token())
                        .ifPresent(row -> row.finish(attempt.result(), attempt.errorCode(), attempt.rawErrorCode(),
                                attempt.errorDetail(), now));
                apply(task, attempt, now);
                lectureHpTaskRepository.flush();
                return task;
            });
            if (recorded != null) {
                report(recorded);
            }
        } catch (ObjectOptimisticLockingFailureException conflict) {
            log.warn("late result ignored: 결과를 기록하는 사이 다시 선점됐다 taskId={}, result={}", taskId,
                    attempt.result());
        }
    }

    private void apply(LectureHpTask task, Attempt attempt, LocalDateTime now) {
        LectureHpTaskResultHandler handler = handlers.get(task.getPurpose());
        switch (attempt.result()) {
            case SUCCEEDED -> {
                task.complete(LectureHpTaskOutcome.SUCCEEDED, now);
                handler.onSucceeded(task);
            }
            // 보류의 확정 거절만 정상 결과다. 반환·지급의 확정 거절은 바로 DEAD다 (D57 (6))
            case TERMINAL_REJECTION -> {
                if (task.getTaskType() == LectureHpTaskType.HOLD) {
                    task.complete(LectureHpTaskOutcome.REJECTED, now);
                    handler.onRejected(task);
                } else {
                    task.markDead(attempt.errorCode(), now);
                }
            }
            case INVARIANT_VIOLATION, LEASE_EXPIRED -> task.markDead(attempt.errorCode(), now);
            case RETRYABLE, UNKNOWN_RESULT -> {
                if (retryPolicy.isExhausted(task.getAttemptCount())) {
                    task.markDead(attempt.errorCode(), now);
                } else {
                    task.scheduleRetry(attempt.errorCode(), retryPolicy.nextAttemptAt(task.getAttemptCount(), now,
                            ThreadLocalRandom.current().nextDouble()), now);
                }
            }
        }
        if (task.getStatus() == LectureHpTaskStatus.DEAD) {
            notifyDead(task);
        }
    }

    private void notifyDead(LectureHpTask task) {
        LectureHpTaskResultHandler handler = handlers.get(task.getPurpose());
        if (handler != null) {
            handler.onDead(task);
        }
    }

    private void report(LectureHpTask task) {
        switch (task.getStatus()) {
            case COMPLETED -> {
                if (task.getOutcome() == LectureHpTaskOutcome.REJECTED) {
                    metrics.recordRejected();
                } else {
                    metrics.recordSucceeded();
                }
            }
            case DEAD -> {
                metrics.recordDead();
                log.error("{} taskId={}, type={}, purpose={}, requestId={}, orderItemId={}, amountHp={}, attempts={}, "
                                + "totalAttempts={}, error={}", DEAD_LOG, task.getId(), task.getTaskType(),
                        task.getPurpose(), task.getRequestId(), task.getOrderItemId(), task.getAmountHp(),
                        task.getAttemptCount(), task.getTotalAttemptCount(), task.getLastErrorCode());
            }
            case PENDING, PROCESSING -> metrics.recordRetry();
        }
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static String describe(Throwable e) {
        Throwable cause = e.getCause();
        return e.getClass().getSimpleName() + ": " + e.getMessage()
                + (cause == null ? "" : " / " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
    }

    private static Map<LectureHpTaskPurpose, LectureHpTaskResultHandler> byPurpose(
            List<LectureHpTaskResultHandler> handlers) {
        Map<LectureHpTaskPurpose, LectureHpTaskResultHandler> map = new EnumMap<>(LectureHpTaskPurpose.class);
        for (LectureHpTaskResultHandler handler : handlers) {
            if (map.put(handler.purpose(), handler) != null) {
                throw new IllegalStateException("HP 작업 목적 하나에 결과 처리기가 둘입니다. purpose=" + handler.purpose());
            }
        }
        return map;
    }

    /** 선점한 작업과 선점 표식 */
    private record Claim(LectureHpTask task, UUID token) {
    }

    /** 시도 한 번의 결과 */
    private record Attempt(LectureHpAttemptResult result, String errorCode, String rawErrorCode, String errorDetail) {
    }
}
