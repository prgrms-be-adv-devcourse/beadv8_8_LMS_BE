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
import com.hapbang.shared.billing.domain.BillingFailureCode;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.out.BillingCallException;
import com.hapbang.shared.billing.out.BillingCallResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * HP 작업 실행. 대기 중인 작업 하나를 지갑에 요청하고 결과를 기록한다.
 * 업무 처리(거절·취소·마감·확인 등) 직후 한 번, 그 뒤로는 HP 작업 재시도 배치가 부른다.
 * <ul>
 *     <li>작업을 기록한 트랜잭션이 커밋된 뒤에 부른다. 지갑 호출은 트랜잭션 밖에서 하고, 결과 기록만 짧은 트랜잭션으로 한다.</li>
 *     <li>결과
 *     <ul>
 *         <li>{@code Succeeded} (같은 요청을 다시 보냈으면 처음 결과) → {@code DONE}</li>
 *         <li>{@code Failed} (지갑이 처리하지 않고 실패로 응답함) → 바로 {@code DEAD}. 다시 보내도 결과가 같으므로 지갑 코드·설명을
 *         마지막 오류로 기록한다 (목록 밖 코드·멱등 충돌도 같다. 원래 코드와 HTTP 상태를 함께 남긴다)</li>
 *         <li>결과를 받지 못함({@code BillingCallException}: 결과 불명·보내지 못함) → 재시도 정책에 따라 다음 시도 시각을 정하고,
 *         최대 횟수에 닿으면 {@code DEAD}. 지갑 호출 전후의 예상하지 못한 예외도 같은 요청 ID로 다시 보내면 되므로 같게 다룬다</li>
 *     </ul></li>
 *     <li>격리되면 {@code [HP_TASK_DEAD]} 오류 로그를 남긴다 (로그에서 이 문자열로 찾는다).</li>
 *     <li>실패해도 예외를 밖으로 던지지 않는다. 업무 상태(거절·취소·만료)는 이미 커밋됐다.
 *     결과 기록 같은 처리 중 예외(DB 오류 등)도 로그만 남기고, 작업은 대기로 남아 재시도 배치가 다시 실행한다
 *     (같은 요청 ID라 지갑은 한 번만 처리한다). 여러 작업을 이어서 실행하는 호출(모집 전체 취소)도 앞 작업 때문에 멈추지 않는다.</li>
 *     <li>같은 작업을 두 곳에서 동시에 실행해도 같은 요청 ID라 지갑은 한 번만 처리한다.
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
        try {
            run(taskId);
        } catch (RuntimeException unexpected) {
            log.error("HP 작업 실행 중 예외, 작업은 대기로 남아 재시도 배치가 다시 실행한다 taskId={}", taskId, unexpected);
        }
    }

    private void run(Long taskId) {
        HpTask task = hpTaskSupport.getById(taskId);
        if (!task.isRunnable(LocalDateTime.now(clock))) {
            return;
        }
        BillingCallResult<?> result;
        try {
            result = request(task);
        } catch (BillingCallException unknown) {
            log.warn("HP 작업 결과를 받지 못함, 재시도 정책에 따라 다시 실행한다 taskId={}, key={}, kind={}, httpStatus={}",
                    taskId, task.getIdempotencyKey(), unknown.getKind(), unknown.getHttpStatus(), unknown);
            retryLater(taskId, describe(unknown));
            return;
        } catch (RuntimeException unexpected) {
            // 지갑 호출 전후의 예상하지 못한 오류(요청 만들기 등). 같은 요청 ID라 다시 보내도 안전하므로 재시도 횟수를 쓰며 다시 실행한다
            log.error("HP 작업 실행 중 예상하지 못한 예외, 재시도 정책에 따라 다시 실행한다 taskId={}, key={}", taskId,
                    task.getIdempotencyKey(), unexpected);
            retryLater(taskId, unexpected.getClass().getSimpleName() + ": " + unexpected.getMessage());
            return;
        }
        switch (result) {
            case BillingCallResult.Succeeded<?> succeeded -> {
                log.debug("HP 작업 지갑 처리 taskId={}, result={}", taskId, succeeded.value());
                record(taskId, pending -> pending.markDone(LocalDateTime.now(clock)));
            }
            // 지갑이 처리하지 않고 실패로 응답함: 다시 보내도 결과가 같으므로 바로 격리한다 ([HP_TASK_DEAD] 오류 로그)
            case BillingCallResult.Failed<?> failed ->
                    record(taskId, pending -> pending.markDead(describe(failed), LocalDateTime.now(clock)));
        }
    }

    private void retryLater(Long taskId, String error) {
        double random = ThreadLocalRandom.current().nextDouble();
        record(taskId, pending -> pending.recordFailure(error, hpTaskRetryPolicy, LocalDateTime.now(clock), random));
    }

    /**
     * 작업 종류에 맞는 지갑 요청을 보낸다. 값을 돌려주는 switch 식이라 작업 종류가 늘었는데 분기를 빠뜨리면 컴파일되지 않는다
     * (분기 없이 지갑을 부르지 않고 완료로 기록되는 일을 막는다).
     */
    private BillingCallResult<?> request(HpTask task) {
        return switch (task.getTaskType()) {
            case RELEASE -> billingClient.release(toReleaseCommand(task));
            case PENALTY -> billingClient.settlePenalty(toPenaltyCommand(task));
            case PAYOUT -> billingClient.payout(toPayoutCommand(task));
        };
    }

    /**
     * 지갑 거절의 마지막 오류: {@code 코드: 설명}. 목록 밖 코드면 원래 코드와 HTTP 상태를 함께 남긴다
     * (예: {@code UNMAPPED(INVALID_INTERNAL_API_KEY, 401): ...}).
     */
    private static String describe(BillingCallResult.Failed<?> failed) {
        String code = failed.code() == BillingFailureCode.UNMAPPED
                ? "UNMAPPED(" + failed.rawCode() + ", " + failed.httpStatus() + ")"
                : failed.code().name();
        return failed.detail() == null ? code : code + ": " + failed.detail();
    }

    /** 결과를 받지 못한 실패의 마지막 오류: {@code 종류 [HTTP 상태] [지갑 코드]: 메시지} */
    private static String describe(BillingCallException unknown) {
        StringBuilder error = new StringBuilder(unknown.getKind().name());
        if (unknown.getHttpStatus() > 0) {
            error.append(' ').append(unknown.getHttpStatus());
        }
        if (unknown.getRawCode() != null) {
            error.append(' ').append(unknown.getRawCode());
        }
        return error.append(": ").append(unknown.getMessage()).toString();
    }

    /**
     * 지급 작업 → Billing 지급 요청 (#282). 멱등 키를 지급 요청 ID로 쓰고, 보류에서 기록한 금액(출연료)만큼 게스트에게 지급한다.
     * 전체 지급이 아니라 금액을 정한 지급이다: 모집글 보류에는 다른 출연의 몫이 함께 있다.
     */
    private PayoutCommand toPayoutCommand(HpTask task) {
        return PayoutCommand.partial(task.getMemberId(), BillingReferenceType.valueOf(task.getRefType().name()),
                task.getRefId(), task.getIdempotencyKey(), task.getReceiverId(), sourceTypeOf(task),
                task.getAppearanceId(), task.getPayoutAmount());
    }

    /** 지급 원천 종류: 요청서 보류면 {@code APPEARANCE_REQUEST}, 모집글 보류면 {@code RECRUIT_APPEARANCE} (원천 ID는 출연 ID) */
    private static SettlementSourceType sourceTypeOf(HpTask task) {
        return task.getRefType() == HpTaskRefType.APPEARANCE_RECRUIT
                ? SettlementSourceType.RECRUIT_APPEARANCE : SettlementSourceType.APPEARANCE_REQUEST;
    }

    /**
     * 위약금 정산 작업 → Billing 위약금 정산 요청 (#239). 멱등 키를 취소 ID로 쓰고, 보류에서 위약금은 게스트에게 지급하고
     * 나머지는 호스트에게 반환한다. 지급 원천은 출연(요청서 출처 {@code APPEARANCE_REQUEST}, 모집 출처 {@code RECRUIT_APPEARANCE})이다.
     */
    private PenaltyCommand toPenaltyCommand(HpTask task) {
        return new PenaltyCommand(task.getMemberId(), BillingReferenceType.valueOf(task.getRefType().name()),
                task.getRefId(), task.getIdempotencyKey(), task.getReceiverId(), sourceTypeOf(task),
                task.getAppearanceId(),
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
