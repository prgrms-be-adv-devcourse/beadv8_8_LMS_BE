package com.hapbang.lecture.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
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
 * 강의 HP 작업: 보류·반환·지급을 지갑 내부 API로 보내는 요청 한 건 (D57, #275).
 * <ul>
 *     <li>업무 상태와 같은 강의 트랜잭션에서 만든다. 지갑 호출은 커밋 뒤 트랜잭션 밖에서 하고, 결과는 짧은 트랜잭션으로 기록한다.</li>
 *     <li>선점은 저장소의 조건부 UPDATE로 한다({@code LectureHpTaskRepository.claim}). 결과는 선점 표식({@code claimToken})이
 *     같을 때만 기록하고, 선점이 바뀐 뒤 늦게 온 결과는 버전 충돌로 막는다.</li>
 *     <li>요청 ID는 지갑 멱등 키다. 같은 요청 ID로 다시 보내면 지갑은 처음 결과를 돌려준다.</li>
 *     <li>삭제하지 않는다.</li>
 * </ul>
 */
@Entity
@Table(name = "lecture_hp_task", schema = "lectures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LectureHpTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LectureHpTaskType taskType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LectureHpTaskPurpose purpose;

    @Column(nullable = false, unique = true, length = 200)
    private String requestId;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long orderItemId;

    @Column(nullable = false)
    private Long payerUserId;

    /** 지급받는 판매자. 지급 작업에만 있다 */
    private Long receiverUserId;

    /** 항목 가격. 지갑 결과 금액이 이 값과 다르면 불변식 위반이다 (D43) */
    @Column(nullable = false)
    private long amountHp;

    @Column(nullable = false, length = 64)
    private String payloadFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LectureHpTaskStatus status;

    @Enumerated(EnumType.STRING)
    private LectureHpTaskOutcome outcome;

    /** 현재 자동 재시도 주기의 선점 횟수. 관리자 재시도 때 0이 된다 */
    @Column(nullable = false)
    private int attemptCount;

    /** 관리자 재시도를 포함한 누적 선점 횟수. 시도 행 번호다 */
    @Column(nullable = false)
    private int totalAttemptCount;

    @Column(nullable = false)
    private int manualRetryCount;

    @Column(nullable = false)
    private LocalDateTime nextAttemptAt;

    private UUID claimToken;

    private LocalDateTime leaseUntil;

    @Column(length = 100)
    private String lastErrorCode;

    private LocalDateTime lastFailedAt;

    private Long lastRetriedByUserId;

    private LocalDateTime lastRetriedAt;

    private LocalDateTime completedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    private LectureHpTask(LectureHpTaskPurpose purpose, String requestId, Long orderId, Long orderItemId,
                          Long payerUserId, Long receiverUserId, long amountHp, LocalDateTime now) {
        this.taskType = purpose.getTaskType();
        this.purpose = purpose;
        this.requestId = requestId;
        this.orderId = orderId;
        this.orderItemId = orderItemId;
        this.payerUserId = payerUserId;
        this.receiverUserId = receiverUserId;
        this.amountHp = amountHp;
        this.payloadFingerprint = fingerprint(purpose, requestId, orderId, orderItemId, payerUserId, receiverUserId,
                amountHp);
        this.status = LectureHpTaskStatus.PENDING;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 작업을 만든다. 지급 작업만 받는 판매자가 있다.
     *
     * @param requestId 지갑 멱등 키. 업무 요청마다 안정적으로 만든다 (예: {@code LECTURE-CANCEL-{항목 ID}})
     */
    public static LectureHpTask create(LectureHpTaskPurpose purpose, String requestId, Long orderId, Long orderItemId,
                                       Long payerUserId, Long receiverUserId, long amountHp, LocalDateTime now) {
        Objects.requireNonNull(purpose, "작업 목적은 필수입니다.");
        Objects.requireNonNull(requestId, "요청 ID는 필수입니다.");
        Objects.requireNonNull(orderId, "주문 ID는 필수입니다.");
        Objects.requireNonNull(orderItemId, "주문 항목 ID는 필수입니다.");
        Objects.requireNonNull(payerUserId, "구매자 ID는 필수입니다.");
        if ((purpose.getTaskType() == LectureHpTaskType.PAYOUT) != (receiverUserId != null)) {
            throw new IllegalArgumentException("지급받는 판매자는 지급 작업에만 있습니다. purpose=" + purpose);
        }
        if (amountHp <= 0) {
            throw new IllegalArgumentException("작업 금액은 0보다 커야 합니다. amountHp=" + amountHp);
        }
        return new LectureHpTask(purpose, requestId, orderId, orderItemId, payerUserId, receiverUserId, amountHp, now);
    }

    /** 요청 값이 같은지. 같은 요청 ID인데 다르면 충돌이다 (D57 (2)) */
    public boolean hasSamePayload(LectureHpTask other) {
        return payloadFingerprint.equals(other.payloadFingerprint);
    }

    public boolean isClaimedBy(UUID token) {
        return status == LectureHpTaskStatus.PROCESSING && token.equals(claimToken);
    }

    /** 성공·보류 거절로 끝낸다 */
    public void complete(LectureHpTaskOutcome outcome, LocalDateTime now) {
        this.status = LectureHpTaskStatus.COMPLETED;
        this.outcome = outcome;
        this.completedAt = now;
        releaseClaim(now);
    }

    /** 실패를 남기고 다음 시도 시각까지 기다린다 */
    public void scheduleRetry(String errorCode, LocalDateTime nextAttemptAt, LocalDateTime now) {
        recordFailure(errorCode, now);
        this.status = LectureHpTaskStatus.PENDING;
        this.nextAttemptAt = nextAttemptAt;
        releaseClaim(now);
    }

    /** 자동 처리를 멈춘다. 관리자가 원인을 확인한 뒤 재시도한다 */
    public void markDead(String errorCode, LocalDateTime now) {
        recordFailure(errorCode, now);
        this.status = LectureHpTaskStatus.DEAD;
        releaseClaim(now);
    }

    /**
     * 관리자 재시도: {@code DEAD} → {@code PENDING}. 자동 재시도 횟수만 0으로 하고 누적 횟수·마지막 오류는 남긴다 (D57 (9)).
     * 같은 요청 ID로 다시 보내므로 HP가 두 번 움직이지 않는다.
     */
    public void retryManually(Long adminUserId, LocalDateTime now) {
        if (status != LectureHpTaskStatus.DEAD) {
            throw new LectureException(LectureErrorCode.HP_TASK_NOT_DEAD);
        }
        this.status = LectureHpTaskStatus.PENDING;
        this.attemptCount = 0;
        this.manualRetryCount++;
        this.lastRetriedByUserId = adminUserId;
        this.lastRetriedAt = now;
        this.nextAttemptAt = now;
        this.updatedAt = now;
    }

    private void recordFailure(String errorCode, LocalDateTime now) {
        this.lastErrorCode = errorCode;
        this.lastFailedAt = now;
    }

    private void releaseClaim(LocalDateTime now) {
        this.claimToken = null;
        this.leaseUntil = null;
        this.updatedAt = now;
    }

    /** 요청 값의 SHA-256. 같은 요청 ID로 다른 작업을 만들려는 실수를 찾는다 */
    static String fingerprint(LectureHpTaskPurpose purpose, String requestId, Long orderId, Long orderItemId,
                              Long payerUserId, Long receiverUserId, long amountHp) {
        String payload = String.join("|", purpose.name(), requestId, String.valueOf(orderId),
                String.valueOf(orderItemId), String.valueOf(payerUserId), String.valueOf(receiverUserId),
                String.valueOf(amountHp));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", e);
        }
    }
}
