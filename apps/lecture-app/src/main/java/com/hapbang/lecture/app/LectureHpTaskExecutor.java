package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

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
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.out.LectureHpTaskAttemptRepository;
import com.hapbang.lecture.out.LectureHpTaskRepository;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.OrderItemCouponRepository;
import com.hapbang.lecture.out.OrderItemWithOrderId;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.HoldVoidOutcome;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldPromotionDto;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.HoldVoidCommand;
import com.hapbang.shared.billing.dto.HoldVoidResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PromotionRestorationDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;
import com.hapbang.shared.billing.out.BillingApiClient;
import com.hapbang.shared.billing.out.BillingCallException;
import com.hapbang.shared.billing.out.BillingCallResult;

import lombok.extern.slf4j.Slf4j;

/**
 * 강의 HP 작업 실행기 (D57, D62, #275, D73). 작업 하나를 선점해 지갑 내부 API로 보내고 결과를 기록한다.
 * <ol>
 *     <li>선점(짧은 트랜잭션): 조건부 UPDATE로 {@code PROCESSING} + 선점 표식 + 시도 행. 선점이 만료된 앞 시도는 {@code LEASE_EXPIRED}로 닫는다.</li>
 *     <li>지갑 호출(트랜잭션 밖): {@link BillingApiClient}. 실패를 작업 유형별로 나눈다({@link LectureHpTaskClassifier}).</li>
 *     <li>결과 기록(짧은 트랜잭션): 선점 표식이 같을 때만 작업·시도 행을 바꾸고 목적별 {@link LectureHpTaskResultHandler}를 부른다.
 *     선점이 바뀐 뒤 늦게 온 결과는 버리고 경고만 남긴다.</li>
 * </ol>
 * 업무 요청 안의 즉시 실행과 재시도 배치가 같은 {@link #execute}를 쓴다. 강의 트랜잭션 안에서 부르면 실패한다.
 * 실행 중 예외(DB 오류 등)는 밖으로 던지지 않는다. 작업은 남아 선점이 만료된 뒤 같은 요청 ID로 다시 실행된다.
 * <p>
 * 지갑 회로가 열려 있으면 선점하지 않는다(D79). 선점하면 시도 횟수가 오르므로, 보내지도 않은 거절로 횟수를 써 {@code DEAD}가 되지 않게
 * 하기 위해서다. 배치·주문 즉시 보류·구매 취소 즉시 반환이 모두 이 확인을 지난다. 작업은 그대로 남고(처리 상태 {@code PROCESSING})
 * 다음 배치가 다시 본다. 시험 중(HALF_OPEN)에 실제로 보낸 호출의 실패는 D57대로 센다.
 */
@Slf4j
@Service
public class LectureHpTaskExecutor {

    static final String DEAD_LOG = "[LECTURE_HP_TASK_DEAD]";
    /** 보류 무효화의 반환 금액이 예상과 다를 때 붙이는 alert 표식. 작업 오류 코드 {@link #AMOUNT_MISMATCH}와 다르다 */
    static final String VOID_AMOUNT_MISMATCH_LOG = "[LECTURE_HP_VOID_AMOUNT_MISMATCH]";
    static final String RESULT_HANDLER_MISSING = "RESULT_HANDLER_MISSING";
    static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    static final String LEASE_EXPIRED = "LEASE_EXPIRED";

    private final LectureHpTaskRepository lectureHpTaskRepository;
    private final LectureHpTaskAttemptRepository lectureHpTaskAttemptRepository;
    private final BillingApiClient billingApiClient;
    private final OrderItemCouponRepository orderItemCouponRepository;
    private final LectureOrderRepository lectureOrderRepository;
    private final Map<LectureHpTaskPurpose, LectureHpTaskResultHandler> handlers;
    private final LectureHpTaskProperties properties;
    private final LectureHpTaskRetryPolicy retryPolicy;
    private final LectureHpTaskMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    /** 지갑 회로가 열려 실행을 멈춘 상태인지. 멈춤·재개 로그를 상태가 바뀔 때만 남기려고 둔다 (D79) */
    private final AtomicBoolean stoppedByOpenCircuit = new AtomicBoolean();

    public LectureHpTaskExecutor(LectureHpTaskRepository lectureHpTaskRepository,
                                 LectureHpTaskAttemptRepository lectureHpTaskAttemptRepository,
                                 BillingApiClient billingApiClient, List<LectureHpTaskResultHandler> handlers,
                                 OrderItemCouponRepository orderItemCouponRepository,
                                 LectureOrderRepository lectureOrderRepository,
                                 LectureHpTaskProperties properties, LectureHpTaskRetryPolicy retryPolicy,
                                 LectureHpTaskMetrics metrics, PlatformTransactionManager transactionManager,
                                 @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureHpTaskRepository = lectureHpTaskRepository;
        this.lectureHpTaskAttemptRepository = lectureHpTaskAttemptRepository;
        this.billingApiClient = billingApiClient;
        this.orderItemCouponRepository = orderItemCouponRepository;
        this.lectureOrderRepository = lectureOrderRepository;
        this.handlers = byPurpose(handlers);
        this.properties = properties;
        this.retryPolicy = retryPolicy;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * 작업 하나를 실행한다. 선점하지 못하면(다른 실행 중, 시각 전, 끝남) 아무것도 하지 않는다.
     * 지갑 회로가 열려 있으면 선점하지 않고 돌아온다. 시도 횟수·시도 행·작업은 바꾸지 않는다 (D79)
     */
    public void execute(Long taskId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("HP 작업은 강의 트랜잭션 밖에서 실행한다. taskId=" + taskId);
        }
        if (!canCallBilling()) {
            log.debug("지갑 회로가 열려 HP 작업을 선점하지 않는다 taskId={}", taskId);
            return;
        }
        try {
            run(taskId);
        } catch (RuntimeException unexpected) {
            log.error("HP 작업을 실행하다 예외가 나 선점이 만료된 뒤 다시 실행한다 taskId={}", taskId, unexpected);
        }
    }

    /**
     * 지금 지갑에 보낼 수 있는지 (D79). 지갑 회로가 열려 있으면(OPEN·FORCED_OPEN) {@code false}이고, 시험 중(HALF_OPEN)은 {@code true}다.
     * {@link #execute}가 선점 전에, 배치가 회차 시작과 작업마다 본다. 로그는 멈춤·재개가 바뀔 때만 남겨 장애 동안 매분 쌓이지 않게 한다
     */
    public boolean canCallBilling() {
        boolean circuitOpen = billingApiClient.isCircuitOpen();
        if (circuitOpen && stoppedByOpenCircuit.compareAndSet(false, true)) {
            log.warn("지갑 회로가 열려 HP 작업 실행을 멈춘다. 회로가 시험 중으로 넘어가면 다시 실행한다");
        } else if (!circuitOpen && stoppedByOpenCircuit.compareAndSet(true, false)) {
            log.info("지갑 회로가 열려 있지 않아 HP 작업 실행을 다시 시작한다");
        }
        return !circuitOpen;
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
        if (claim.payloadError() != null) {
            record(claim, new Attempt(LectureHpAttemptResult.INVARIANT_VIOLATION, "PROMOTION_PAYLOAD_INVALID", null,
                    claim.payloadError()));
            return;
        }
        record(claim, call(claim));
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
        var coupons = orderItemCouponRepository.findByOrderItemIdInOrderByOrderItemIdAscApplyOrderAsc(
                List.of(task.getOrderItemId()));
        Optional<OrderItem> item = lectureOrderRepository.findItemsWithOrderId(List.of(task.getOrderItemId())).stream()
                .findFirst().map(OrderItemWithOrderId::item);
        var expected = item.map(OrderItem::getPlatformDiscountHp);
        // 지급 요청의 출처 제목(#872, D85 (6)): 작업에 저장하지 않고 구매 때 저장한 제목을 여기서 읽는다. 항목이 없으면 제목 없이 보낸다
        String sourceTitle = item.map(OrderItem::getLectureTitleSnapshot).orElse(null);
        try {
            List<HoldPromotionDto> promotions = LectureHpPromotions.of(coupons);
            long amount = LectureHpPromotions.total(promotions);
            if (task.getAmountHp() == 0 && amount == 0) {
                throw new IllegalStateException("구매자와 플랫폼 부담이 모두 0인 작업은 지갑을 부르지 않습니다.");
            }
            if (expected.isPresent() && expected.get() != amount) {
                throw new IllegalStateException("항목의 플랫폼 부담과 쿠폰 스냅샷 합이 다릅니다.");
            }
            return new Claim(task, token, promotions, null, sourceTitle);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException invalid) {
            return new Claim(task, token, List.of(), invalid.getMessage(), sourceTitle);
        }
    }

    /** 지갑을 부른다. 트랜잭션 밖이다 */
    private Attempt call(Claim claim) {
        LectureHpTask task = claim.task();
        try {
            BillingCallResult<?> result = switch (task.getTaskType()) {
                case HOLD -> billingApiClient.hold(new HoldCommand(task.getPayerUserId(),
                        BillingReferenceType.LECTURE_ORDER_ITEM, task.getOrderItemId(), task.getAmountHp(), claim.promotions()));
                case RELEASE -> billingApiClient.release(ReleaseCommand.full(task.getPayerUserId(),
                        BillingReferenceType.LECTURE_ORDER_ITEM, task.getOrderItemId(), task.getRequestId()));
                case PAYOUT -> billingApiClient.payout(PayoutCommand.full(task.getPayerUserId(),
                        BillingReferenceType.LECTURE_ORDER_ITEM, task.getOrderItemId(), task.getRequestId(),
                        task.getReceiverUserId(), SettlementSourceType.LECTURE_ORDER_ITEM, task.getOrderItemId(),
                        claim.sourceTitle()));
                // 지갑 멱등 키는 참조 대상이라 요청 ID를 보내지 않는다 (D73)
                case VOID -> billingApiClient.voidHold(new HoldVoidCommand(task.getPayerUserId(),
                        BillingReferenceType.LECTURE_ORDER_ITEM, task.getOrderItemId()));
            };
            return switch (result) {
                case BillingCallResult.Succeeded<?> succeeded -> verifyAmount(task, claim.promotions(), succeeded.value());
                case BillingCallResult.Failed<?> failed -> new Attempt(
                        LectureHpTaskClassifier.classify(task.getTaskType(), failed.code()), failed.code().name(),
                        failed.rawCode(), failed.httpStatus() + " " + failed.detail());
            };
        } catch (BillingCallException e) {
            // 선점 전에 회로를 봤어도 그 사이 회로가 막히면(시험 호출 몫을 다른 요청이 다 씀, 다른 호출의 실패로 열림)
            // CIRCUIT_OPEN(RETRYABLE)이 온다. 선점이 이미 횟수를 올렸으므로 그대로 한 번으로 센다(D79, 드문 예외)
            log.warn("HP 작업의 지갑 호출이 실패했다 taskId={}, requestId={}, kind={}, httpStatus={}", task.getId(),
                    task.getRequestId(), e.getKind(), e.getHttpStatus());
            return new Attempt(LectureHpTaskClassifier.classify(e.getKind()), e.getKind().name(), e.getRawCode(),
                    describe(e));
        } catch (RuntimeException e) {
            // 그 밖의 예외는 지갑이 처리했는지 알 수 없다 (D57 (5))
            log.warn("HP 작업의 지갑 호출 중 예외가 났다 taskId={}, requestId={}", task.getId(), task.getRequestId(), e);
            return new Attempt(LectureHpAttemptResult.UNKNOWN_RESULT, LectureHpAttemptResult.UNKNOWN_RESULT.name(),
                    null, describe(e));
        }
    }

    /** 결과 금액이 항목 가격과 다르면 불변식 위반이다 (D43). 같은 요청의 재현(replayed)은 정상이다 */
    private static Attempt verifyAmount(LectureHpTask task, List<HoldPromotionDto> promotions, Object value) {
        long promotionHp = LectureHpPromotions.total(promotions);
        if (value instanceof HoldVoidResultDto voided) {
            return verifyVoid(task, promotions, voided);
        }
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
        boolean matches = switch (value) {
            case HoldResultDto hold -> hold.promotionHeldHp() == promotionHp
                    && hold.promotions().equals(promotions);
            case ReleaseResultDto release -> release.promotionRestoredHp() == promotionHp
                    && LectureHpPromotions.restored(promotions, release.promotionRestorations());
            case PayoutResultDto payout -> promotionHp == 0
                    ? (payout.promotionPaidHp() == null || payout.promotionPaidHp() == 0)
                            && (payout.buyerPaidHp() == null || payout.buyerPaidHp() == task.getAmountHp())
                    : payout.promotionPaidHp() != null && payout.promotionPaidHp() == promotionHp
                            && payout.buyerPaidHp() != null
                            && payout.buyerPaidHp() == task.getAmountHp() - promotionHp;
            default -> false;
        };
        if (!matches) {
            return new Attempt(LectureHpAttemptResult.INVARIANT_VIOLATION, AMOUNT_MISMATCH, null,
                    "플랫폼 재원 결과가 작업의 쿠폰 스냅샷과 다릅니다: " + value);
        }
        List<PromotionRestorationDto> restorations = value instanceof ReleaseResultDto release
                ? release.promotionRestorations() : List.of();
        return new Attempt(LectureHpAttemptResult.SUCCEEDED, null, null, null, restorations);
    }

    /**
     * 보류 무효화 결과 (D73 (4)). 남은 보류를 반환했으면({@code RELEASED}) 항목 가격, 보류가 없거나 이미 끝났으면 0이 정상이다.
     * 지갑은 이미 그 보류를 끝냈고 같은 요청에는 처음 결과만 돌려주므로, 예상과 달라도 DEAD로 두지 않고 성공으로 기록해 주문을 끝낸다.
     * 지갑 결과는 강의에 저장하지 않으며 기준 기록은 지갑의 무효화 기록이다
     */
    private static Attempt verifyVoid(LectureHpTask task, List<HoldPromotionDto> promotions, HoldVoidResultDto result) {
        List<HoldPromotionDto> expected = result.outcome() == HoldVoidOutcome.RELEASED ? promotions : List.of();
        if (result.promotionRestoredHp() != LectureHpPromotions.total(expected)
                || !LectureHpPromotions.restored(expected, result.promotionRestorations())) {
            return new Attempt(LectureHpAttemptResult.INVARIANT_VIOLATION, AMOUNT_MISMATCH, null,
                    "무효화의 플랫폼 예산 복구 결과가 다릅니다: " + result);
        }
        long expectedHp = result.outcome() == HoldVoidOutcome.RELEASED ? task.getAmountHp() : 0L;
        if (result.releasedHp() != expectedHp) {
            log.error(VOID_AMOUNT_MISMATCH_LOG + " 보류 무효화의 반환 금액이 예상과 달라도 성공으로 기록한다 taskId={}, "
                            + "orderItemId={}, outcome={}, releasedHp={}, expectedHp={}, replayed={}", task.getId(),
                    task.getOrderItemId(), result.outcome(), result.releasedHp(), expectedHp, result.replayed());
        } else if (result.outcome() == HoldVoidOutcome.ALREADY_CLOSED) {
            log.warn("보류 무효화 대상 보류가 이미 끝나 있었다 taskId={}, orderItemId={}, replayed={}", task.getId(),
                    task.getOrderItemId(), result.replayed());
        } else {
            log.info("보류 무효화를 마쳤다 taskId={}, orderItemId={}, outcome={}, releasedHp={}, replayed={}", task.getId(),
                    task.getOrderItemId(), result.outcome(), result.releasedHp(), result.replayed());
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
                    log.warn("선점이 바뀐 뒤 온 HP 작업 결과를 버린다 taskId={}, result={}", taskId,
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
            // 작업이 다시 선점됐거나, 결과 처리기가 바꾼 행(반환 지연 회차를 올린 주문 항목 등, #818)이 먼저 바뀌었다.
            // 결과 기록 전체가 롤백되고 작업은 선점이 만료된 뒤 다시 실행된다. 어느 행인지 entity로 가린다
            log.warn("결과를 기록하는 사이 같은 행이 먼저 바뀌어 HP 작업 결과를 버린다 taskId={}, result={}, entity={}",
                    taskId, attempt.result(), conflict.getPersistentClassName());
        }
    }

    private void apply(LectureHpTask task, Attempt attempt, LocalDateTime now) {
        LectureHpTaskResultHandler handler = handlers.get(task.getPurpose());
        switch (attempt.result()) {
            case SUCCEEDED -> {
                task.complete(LectureHpTaskOutcome.SUCCEEDED, now);
                handler.onSucceeded(task, attempt.restorations());
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
                log.info("HP 작업을 마쳤다 taskId={}, taskType={}, purpose={}, orderItemId={}, amountHp={}, outcome={}, "
                                + "attemptCount={}", task.getId(), task.getTaskType(), task.getPurpose(),
                        task.getOrderItemId(), task.getAmountHp(), task.getOutcome(), task.getAttemptCount());
            }
            case DEAD -> {
                metrics.recordDead();
                log.error(DEAD_LOG + " HP 작업을 DEAD로 격리했다 taskId={}, taskType={}, purpose={}, requestId={}, "
                                + "orderItemId={}, amountHp={}, attemptCount={}, totalAttemptCount={}, "
                                + "lastErrorCode={}", task.getId(), task.getTaskType(),
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

    /** 선점한 작업과 선점 표식. 출처 제목은 지급 요청에만 싣는 구매 때 강의 제목이다 */
    private record Claim(LectureHpTask task, UUID token, List<HoldPromotionDto> promotions, String payloadError,
                         String sourceTitle) {
    }

    /** 시도 한 번의 결과 */
    private record Attempt(LectureHpAttemptResult result, String errorCode, String rawErrorCode, String errorDetail,
                           List<PromotionRestorationDto> restorations) {
        private Attempt(LectureHpAttemptResult result, String errorCode, String rawErrorCode, String errorDetail) {
            this(result, errorCode, rawErrorCode, errorDetail, List.of());
        }
    }
}
