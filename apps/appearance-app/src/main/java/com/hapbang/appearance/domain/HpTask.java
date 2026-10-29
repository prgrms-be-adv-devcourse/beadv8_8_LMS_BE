package com.hapbang.appearance.domain;

import java.time.LocalDateTime;

import com.hapbang.shared.jpa.BaseEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * HP 작업. 출연 모듈이 Billing에 요청해야 하는 HP 이동(반환 등) 한 건이다.
 * <ul>
 *     <li>업무 상태 변경(예: 요청서 거절)과 같은 트랜잭션에 {@code PENDING}으로 기록한다. 커밋 직후 서버가 죽어도 할 일이 남는다.</li>
 *     <li>커밋 후 실행한다. 성공하면 {@code DONE}, Billing이 거절하면(다시 보내도 결과가 같음) 바로 {@code DEAD}다.</li>
 *     <li>결과를 알 수 없는 실패는 시도 횟수·오류·다음 시도 시각을 남기고 {@code PENDING}으로 두며, 재시도 정책
 *     ({@link HpTaskRetryPolicy})에 따라 HP 작업 재시도 배치가 다시 실행한다. 최대 횟수에 닿으면 {@code DEAD}로 격리한다.</li>
 *     <li>멱등 키는 Billing 요청 ID(반환 요청 ID 등)로 그대로 쓴다. 같은 ID로 다시 보내면 Billing이 처음 결과를 돌려주므로
 *     다시 실행해도 HP가 두 번 움직이지 않는다.</li>
 *     <li>사유는 출연 내부 기록이다. Billing 요청에는 사유가 없다.</li>
 * </ul>
 */
@Entity
@Table(name = "hp_tasks", schema = "appearances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HpTask extends BaseEntity {

    /** 오류 메시지 저장 최대 길이 (DB 컬럼과 같다) */
    static final int LAST_ERROR_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 작업 종류 (Billing에 요청하는 동작: 반환·위약금 정산·지급) */
    @Enumerated(EnumType.STRING)
    private HpTaskType taskType;

    /** 대상 종류 (HP 보류가 걸린 단위) */
    @Enumerated(EnumType.STRING)
    private HpTaskRefType refType;

    /** 대상 ID (요청서·모집글 ID) */
    private Long refId;

    /** 관련 출연 ID. 위약금 정산·지급처럼 출연 단위 작업에서 쓰고, Billing 지급 원천 ID가 된다 (반환은 null) */
    private Long appearanceId;

    /** 보류한 회원 (호스트). 반환받는 회원이자 지급·위약금을 내는 회원 */
    private Long memberId;

    /** 이 작업이 옮기는 HP 전체. 1 이상. 위약금 정산이면 정산 기준액(= 호스트 반환액 + 게스트 위약금) */
    private long amount;

    /** 받는 회원 (게스트). 위약금 정산·지급에서만 쓴다 */
    private Long receiverId;

    /**
     * {@code amount} 중 받는 회원에게 주는 HP. 위약금 정산이면 위약금(나머지는 호스트에게 반환), 지급이면 {@code amount}와 같다.
     * 위약금 정산·지급에서만 쓴다
     */
    private Long payoutAmount;

    /** 작업 사유 (거절·취소·만료 등). 출연 내부 기록이며 Billing에는 보내지 않는다 */
    @Enumerated(EnumType.STRING)
    private HpTaskReason reason;

    /** Billing 요청 ID(반환 요청 ID 등)로 쓰는 멱등 키. 형식 {@code {refType}-{refId}-{동작}-{순번}} ({@link #requestId}) */
    private String idempotencyKey;

    /** 처리 상태: {@code PENDING} 대기·재시도 중 / {@code DONE} 완료 / {@code DEAD} 격리 */
    @Enumerated(EnumType.STRING)
    private HpTaskStatus status;

    /** 지금까지 Billing에 요청한 횟수 (성공·실패 모두 센다) */
    private int attemptCount;

    /** 다음에 실행할 수 있는 시각. 만들 때는 바로 실행할 수 있게 만든 시각이고, 실패하면 재시도 정책대로 미룬다 */
    private LocalDateTime nextAttemptAt;

    /** 마지막 실패 내용 (최대 1,000자). Billing 거절이면 {@code {오류 코드}: {메시지}}, 그 밖에는 {@code {예외 종류}: {메시지}} */
    private String lastError;

    /** 처리가 끝난 시각 ({@code DONE}·{@code DEAD}가 된 시각) */
    private LocalDateTime completedAt;

    /** 관리자가 다시 시도한 횟수 (#397). 다시 시도하면 시도 횟수는 0으로 돌아가므로 이력은 여기에 남긴다 */
    private int manualRetryCount;

    /** 마지막으로 다시 시도한 관리자 회원 ID */
    private Long lastManualRetryBy;

    /** 마지막으로 다시 시도한 시각 */
    private LocalDateTime lastManualRetryAt;

    /** 낙관적 락. 같은 작업의 결과를 두 곳에서 동시에 기록하면 나중 것을 막는다 */
    @Version
    private Long version;

    private HpTask(HpTaskType taskType, HpTaskRefType refType, Long refId, Long memberId, long amount,
                   HpTaskReason reason, String idempotencyKey, LocalDateTime now) {
        this.taskType = taskType;
        this.refType = refType;
        this.refId = refId;
        this.memberId = memberId;
        this.amount = amount;
        this.reason = reason;
        this.idempotencyKey = idempotencyKey;
        this.status = HpTaskStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = now;
    }

    /**
     * 보류 HP 반환 작업을 만든다.
     *
     * @param hostId 반환받을 호스트
     */
    public static HpTask release(HpTaskRefType refType, Long refId, Long hostId, long amount, HpTaskReason reason,
                                 String idempotencyKey, LocalDateTime now) {
        if (amount < 1) {
            throw new IllegalArgumentException("반환 금액은 1 이상이어야 합니다: " + amount);
        }
        return new HpTask(HpTaskType.RELEASE, refType, refId, hostId, amount, reason, idempotencyKey, now);
    }

    /**
     * 위약금 정산 작업을 만든다 (호스트 일방 취소, ISSUE.md APR-32). 보류에서 위약금은 게스트에게 지급하고 나머지는 호스트에게 반환한다.
     *
     * @param hostId        보류한 호스트 (나머지를 반환받는다)
     * @param baseAmount    정산 기준액 = 이 출연의 출연료
     * @param guestId       위약금을 받는 게스트
     * @param penaltyAmount 위약금 (1 이상, 기준액 이하)
     * @param appearanceId  출연 ID (Billing 지급 원천 ID)
     */
    public static HpTask penalty(HpTaskRefType refType, Long refId, Long hostId, long baseAmount, Long guestId,
                                 long penaltyAmount, Long appearanceId, HpTaskReason reason, String idempotencyKey,
                                 LocalDateTime now) {
        if (penaltyAmount < 1 || penaltyAmount > baseAmount) {
            throw new IllegalArgumentException("위약금은 1 이상, 기준액 이하여야 합니다: penalty=%d, base=%d"
                    .formatted(penaltyAmount, baseAmount));
        }
        HpTask task = new HpTask(HpTaskType.PENALTY, refType, refId, hostId, baseAmount, reason, idempotencyKey, now);
        task.receiverId = guestId;
        task.payoutAmount = penaltyAmount;
        task.appearanceId = appearanceId;
        return task;
    }

    /**
     * 지급 작업을 만든다 (출연 확인, ISSUE.md APR-35 E7). 보류에서 출연료를 게스트에게 지급한다.
     *
     * @param hostId       보류한 호스트 (지급하는 회원)
     * @param amount       지급액 = 이 출연의 출연료
     * @param guestId      받는 게스트
     * @param appearanceId 출연 ID (Billing 지급 원천 ID)
     */
    public static HpTask payout(HpTaskRefType refType, Long refId, Long hostId, long amount, Long guestId,
                                Long appearanceId, HpTaskReason reason, String idempotencyKey, LocalDateTime now) {
        if (amount < 1) {
            throw new IllegalArgumentException("지급 금액은 1 이상이어야 합니다: " + amount);
        }
        HpTask task = new HpTask(HpTaskType.PAYOUT, refType, refId, hostId, amount, reason, idempotencyKey, now);
        task.receiverId = guestId;
        task.payoutAmount = amount;
        task.appearanceId = appearanceId;
        return task;
    }

    /** 위약금 정산에서 호스트에게 반환하는 몫 (기준액 − 위약금) */
    public long releaseAmountOfPenalty() {
        return amount - payoutAmount;
    }

    /**
     * Billing 요청 ID를 만든다. 형식 {@code {refType}-{refId}-{동작}-{순번}} (예: {@code APPEARANCE_REQUEST-12-RELEASE-1}).
     * Billing이 허용하는 문자(영문·숫자·'-'·'_')만 쓴다. 같은 업무 요청이면 항상 같은 값이 나온다.
     */
    public static String requestId(HpTaskRefType refType, Long refId, HpTaskType taskType, int sequence) {
        return "%s-%d-%s-%d".formatted(refType.name(), refId, taskType.name(), sequence);
    }

    /**
     * 출연 1건에 대한 Billing 요청 ID. 형식 {@code {refType}-{refId}-{동작}-APPEARANCE-{출연 ID}}
     * (예: {@code APPEARANCE_RECRUIT-5-RELEASE-APPEARANCE-12}). 모집글 하나의 보류에서 출연별로 반환할 때 마감 반환
     * ({@code …-RELEASE-1})과 겹치지 않게 한다. 출연 하나는 한 가지 방식(합의 취소·불참 등)으로만 끝나므로 출연당 하나다.
     */
    public static String appearanceRequestId(HpTaskRefType refType, Long refId, HpTaskType taskType,
                                             Long appearanceId) {
        return "%s-%d-%s-APPEARANCE-%d".formatted(refType.name(), refId, taskType.name(), appearanceId);
    }

    public boolean isPending() {
        return status == HpTaskStatus.PENDING;
    }

    /** 실행할 차례인지 (대기 중이고 다음 시도 시각이 지났는지) */
    public boolean isRunnable(LocalDateTime now) {
        return status == HpTaskStatus.PENDING && !nextAttemptAt.isAfter(now);
    }

    /** Billing 처리 성공 */
    public void markDone(LocalDateTime now) {
        requirePending();
        this.attemptCount++;
        this.status = HpTaskStatus.DONE;
        this.completedAt = now;
        this.lastError = null;
    }

    /**
     * Billing 처리 실패. 대기 상태로 두고 다음 시도 시각을 정한다.
     *
     * @param retryAt 다음 시도 시각 (보통은 {@link #recordFailure}가 재시도 정책으로 정한다)
     */
    public void markFailed(String error, LocalDateTime retryAt) {
        requirePending();
        this.attemptCount++;
        this.lastError = truncate(error);
        this.nextAttemptAt = retryAt;
    }

    /**
     * 결과를 알 수 없는 실패를 기록한다. 이번 시도까지 최대 횟수에 닿으면 {@code DEAD}로 격리하고,
     * 아니면 재시도 정책에 따라 다음 시도 시각을 정한다.
     *
     * @param random 지터에 쓸 0 이상 1 미만의 난수
     */
    public void recordFailure(String error, HpTaskRetryPolicy retryPolicy, LocalDateTime now, double random) {
        int attempts = attemptCount + 1;
        if (retryPolicy.isExhausted(attempts)) {
            markDead(error, now);
            return;
        }
        markFailed(error, retryPolicy.nextAttemptAt(attempts, now, random));
    }

    /**
     * 격리한다. Billing이 거절했거나(다시 보내도 결과가 같음) 재시도를 다 써도 실패한 경우다.
     * 더는 자동으로 실행하지 않으며, 화면에는 반환 지연(고객센터 확인)으로 보인다.
     * 관리자가 원인을 확인한 뒤 다시 시도할 수 있다 ({@link #reopenForManualRetry}).
     */
    public void markDead(String error, LocalDateTime now) {
        requirePending();
        this.attemptCount++;
        this.status = HpTaskStatus.DEAD;
        this.lastError = truncate(error);
        this.completedAt = now;
    }

    /**
     * 관리자가 격리된 작업을 다시 시도할 수 있게 대기로 되돌린다 (#397).
     * <ul>
     *     <li>업무 상태(거절·확인·취소)는 되돌리지 않는다. 같은 멱등 키로 다시 보내므로 Billing에서 HP가 두 번 움직이지 않는다.</li>
     *     <li>시도 횟수를 0으로 되돌려, 다시 실패하면 평소 재시도 규칙(간격 늘리기, 최대 횟수 뒤 격리)을 처음부터 적용한다.
     *     마지막 오류는 다음 결과가 나올 때까지 남겨 둔다.</li>
     *     <li>누가 언제 다시 시도했는지 기록하고, 다시 시도 횟수를 늘린다.</li>
     * </ul>
     *
     * @throws AppearanceException 격리된 작업이 아님({@code HP_TASK_NOT_DEAD})
     */
    public void reopenForManualRetry(Long adminId, LocalDateTime now) {
        if (status != HpTaskStatus.DEAD) {
            throw new AppearanceException(AppearanceErrorCode.HP_TASK_NOT_DEAD);
        }
        this.status = HpTaskStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = now;
        this.completedAt = null;
        this.manualRetryCount++;
        this.lastManualRetryBy = adminId;
        this.lastManualRetryAt = now;
    }

    private void requirePending() {
        if (status != HpTaskStatus.PENDING) {
            throw new IllegalStateException("대기 중인 HP 작업이 아닙니다: " + status);
        }
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= LAST_ERROR_MAX) {
            return error;
        }
        return error.substring(0, LAST_ERROR_MAX);
    }
}
