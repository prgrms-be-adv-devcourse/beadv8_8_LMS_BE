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
 *     <li>커밋 후 실행해 성공하면 {@code DONE}, 실패하면 시도 횟수·오류·다음 시도 시각을 남기고 {@code PENDING}으로 둔다.</li>
 *     <li>재시도와 격리({@code DEAD})는 재시도 스케줄러가 맡는다 (스케줄러 처리에서 추가).</li>
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

    @Enumerated(EnumType.STRING)
    private HpTaskType taskType;

    /** 대상 종류 (HP 보류가 걸린 단위) */
    @Enumerated(EnumType.STRING)
    private HpTaskRefType refType;

    /** 대상 ID (요청서·모집글 ID) */
    private Long refId;

    /** 관련 출연 ID. 지급·위약금처럼 출연 단위 작업에서 쓴다 */
    private Long appearanceId;

    /** HP를 받는 회원 (반환이면 호스트) */
    private Long memberId;

    private long amount;

    @Enumerated(EnumType.STRING)
    private HpTaskReason reason;

    /** Billing 요청 ID(반환 요청 ID 등)로 쓰는 멱등 키. 형식 {@code {refType}-{refId}-{동작}-{순번}} ({@link #requestId}) */
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    private HpTaskStatus status;

    private int attemptCount;

    /** 다음에 실행할 수 있는 시각 */
    private LocalDateTime nextAttemptAt;

    private String lastError;

    private LocalDateTime completedAt;

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
     * Billing 요청 ID를 만든다. 형식 {@code {refType}-{refId}-{동작}-{순번}} (예: {@code APPEARANCE_REQUEST-12-RELEASE-1}).
     * Billing이 허용하는 문자(영문·숫자·'-'·'_')만 쓴다. 같은 업무 요청이면 항상 같은 값이 나온다.
     */
    public static String requestId(HpTaskRefType refType, Long refId, HpTaskType taskType, int sequence) {
        return "%s-%d-%s-%d".formatted(refType.name(), refId, taskType.name(), sequence);
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
     * @param retryAt 다음 시도 시각 (간격 정책은 재시도 스케줄러에서 정한다)
     */
    public void markFailed(String error, LocalDateTime retryAt) {
        requirePending();
        this.attemptCount++;
        this.lastError = truncate(error);
        this.nextAttemptAt = retryAt;
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
