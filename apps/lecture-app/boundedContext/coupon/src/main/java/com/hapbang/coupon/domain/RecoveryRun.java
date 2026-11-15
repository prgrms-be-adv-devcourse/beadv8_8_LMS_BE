package com.hapbang.coupon.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

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
 * Redis 유실 복구 실행 한 건({@code recovery_run}). 단계는 {@link RecoveryStep} 순서로만 나아간다.
 * <ul>
 *     <li>시작({@link #start}): BLOCKED. 새 epoch 후보는 지금 epoch의 다음 값이다({@link IssueEpochRule#next}). 쿠폰마다 끝나지 않은
 *     복구는 하나뿐이다(DB 부분 유일 인덱스 {@code uk_recovery_run_active}). Redis 차단을 마치면 차단 시각을 남긴다({@link #markBlocked}).</li>
 *     <li>경계 확정({@link #recordBoundary}): BLOCKED·BOUNDARY_FIXED → BOUNDARY_FIXED. 다시 실행하면 경계를 새로 기록하고 이전 미처리 기록을 지운다.
 *     차단 시각부터 진행 중이던 생산자 전송이 모두 끝날 시간이 지나야 한다({@link #requireProducersSettled}).</li>
 *     <li>결과 확정({@link #recordResults}): BOUNDARY_FIXED·RESULTS_CHECKED → RESULTS_CHECKED. 미처리가 남아도 기록한다(다시 확인한다).
 *     재구축을 선점한 뒤(REBUILDING·REBUILT·RESUMING)에는 하지 않는다. 단계를 결과 확정으로 되돌리면 재구축 뒤 취소 금지를 우회하게 된다.</li>
 *     <li>재구축 선점({@link #claimRebuild}): RESULTS_CHECKED·REBUILT(다시 재구축), 또는 진행 중인 선점이 없는 REBUILDING이고 기록된 미처리가
 *     0일 때만 → REBUILDING. RESUMING에서는 하지 않는다(접수가 이미 열렸을 수 있다).</li>
 *     <li>재구축 완료({@link #markRebuilt}): 그 선점의 REBUILDING → REBUILT. Redis가 쓰지 않았다고 확실히 답하면 선점 전 단계로
 *     되돌리고({@link #releaseClaim}), 결과를 알 수 없으면 REBUILDING에 남겨 재구축만 다시 하게 한다({@link #abandonClaim}).</li>
 *     <li>재개 선점({@link #claimResume}): REBUILT, 또는 진행 중인 선점이 없는 RESUMING이고 재개 근거가 있을 때만 → RESUMING.</li>
 *     <li>재개 완료({@link #markResumed}): 그 선점의 RESUMING → RESUMED(끝). Redis가 쓰지 않았다고 확실히 답하면 선점 전 단계로
 *     되돌리고({@link #releaseClaim}), 결과를 알 수 없으면 RESUMING에 남겨 재개만 다시 하게 한다({@link #abandonClaim}).</li>
 *     <li>취소({@link #abort}): 재구축 전(BLOCKED·BOUNDARY_FIXED·RESULTS_CHECKED)에만 → ABORTED(끝). 접수 차단은 풀지 않는다. 이미
 *     ABORTED면 아무것도 바꾸지 않는다(재시도 멱등). 재구축을 선점한 뒤에는 Redis가 이미 새 epoch로 바뀌었을 수 있으므로 재개로만 끝낸다.</li>
 *     <li>예외 취소({@link #abortLostResume}): RESUMING이고 진행 중인 선점이 없고 Redis issue-control이 없거나 runId가 바뀐 것이 확인될 때만
 *     → ABORTED(끝, 사유 기록, 유실 의심). 앞선 재개 결과를 모르는데 재구축한 Redis 기록이 사라져 재개도 재구축도 할 수 없는 경우다. 같은 복구를
 *     되돌리지 않고 끝낸 뒤 새 복구가 유실 의심을 이어받아 처음부터 한다. 조건이 하나라도 맞지 않으면 재개만 다시 한다.</li>
 * </ul>
 * 재구축·재개 선점은 Redis를 바꾸기 전에 커밋한다(낙관적 락). 한 복구에서 진행 중인 선점({@code claimLease} 안)은 하나뿐이라 재구축과 재개가
 * 동시에 Redis를 바꾸지 않는다. 선점 뒤 {@code claimLease}가 지나도록 끝나지 않은 선점은 버려진 것(요청 스레드가 죽었다)으로 보고 같은 단계의
 * 작업이 다시 선점한다.
 * 그 밖의 순서는 {@link CouponDomainError#INVALID_STATUS_TRANSITION}이다. 재개는 언제나 관리자 요청으로만 하고 자동으로 하지 않는다.
 * <p>
 * 접수 기록 유실이 의심되는 복구({@link #isRequestsLost})는 그 쿠폰에서 Redis·DB 어디에도 없는 요청의 상태를 UNKNOWN으로 남긴다
 * ({@link #leavesUnknownAt}).
 */
@Entity
@Table(name = "recovery_run", schema = "coupon")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecoveryRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long couponId;

    /** 복구 전 epoch. 재개 전까지 쿠폰 epoch는 이 값이다 */
    @Column(nullable = false)
    private long previousEpoch;

    /** 재개 때 활성화할 epoch */
    @Column(nullable = false)
    private long newEpoch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecoveryStep step;

    /** Redis 접수 기록을 믿을 수 없다(유실 의심). 그 사이 접수는 결과를 증명할 수 없어 UNKNOWN으로 남을 수 있다 */
    @Column(nullable = false)
    private boolean requestsLost;

    /** requests 재발행을 마친 시각 */
    private Instant republishedAt;

    private Integer republishedCount;

    /** 발급 요청 토픽의 partition별 끝 offset({@code partition:offset,...}) */
    @Column(length = 1000)
    private String kafkaBoundary;

    /** DLT 토픽의 partition별 끝 offset */
    @Column(length = 1000)
    private String dltBoundary;

    /** 마지막 결과 확인의 미처리 합계. 확인 전이면 null */
    private Long unresolvedCount;

    @Column(length = 500)
    private String unresolvedDetail;

    /** 관리자가 적은 재개 근거 */
    @Column(length = 1000)
    private String resumeBasis;

    /** 재개 때 확인한 수량 대사 결과 */
    @Column(length = 500)
    private String resumeEvidence;

    @Column(nullable = false)
    private Long startedBy;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant resumedAt;

    /** 복구를 취소한 관리자. 취소하지 않았으면 null */
    private Long abortedBy;

    private Instant abortedAt;

    /** 예외 취소의 사유({@link #abortLostResume}). 재구축 전 취소이거나 취소하지 않았으면 null */
    @Column(length = 100)
    private String abortReason;

    /**
     * 신규 접수와 재발행 배치가 멈춘 시각(Redis 차단을 마친 시각). 그 뒤 새 전송은 시작되지 않으므로, 이 시각에서 생산자 전송 수명이 지나면
     * 진행 중이던 전송도 모두 끝났다. 차단 전이면 null이다. 경계 재발행이 실패하면(시간 초과한 전송이 아직 진행 중일 수 있다) 그 시각으로 다시 쓴다
     */
    private Instant blockedAt;

    /** 진행 중인 재구축·재개 선점의 시각(선점 토큰). 선점이 없거나 끝났으면 null */
    private Instant claimedAt;

    @Version
    @Column(nullable = false)
    private long version;

    private RecoveryRun(Long couponId, long previousEpoch, Long startedBy, boolean requestsLost, Instant now) {
        this.couponId = Objects.requireNonNull(couponId, "couponId");
        this.previousEpoch = previousEpoch;
        this.newEpoch = IssueEpochRule.next(previousEpoch);
        this.step = RecoveryStep.BLOCKED;
        this.requestsLost = requestsLost;
        this.startedBy = Objects.requireNonNull(startedBy, "startedBy");
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 복구 1단계: 복구 실행과 새 epoch 후보를 기록한다(BLOCKED).
     *
     * @param requestsLost 시작할 때 이미 Redis 유실이 보였다(issue-control·재고 없음, Redis 프로세스 변경, 유실 의심인 채 취소된 앞 복구)
     */
    public static RecoveryRun start(long couponId, long currentEpoch, long adminId, boolean requestsLost, Instant now) {
        return new RecoveryRun(couponId, currentEpoch, adminId, requestsLost, now);
    }

    /** 시작 뒤 Redis 유실이 보였다(차단할 issue-control이 없거나 프로세스가 바뀌었다). 끝난 복구에는 쓰지 않는다 */
    public void markRequestsLost(Instant now) {
        requireStep("유실 기록", RecoveryStep.BLOCKED, RecoveryStep.BOUNDARY_FIXED, RecoveryStep.RESULTS_CHECKED,
                RecoveryStep.REBUILDING, RecoveryStep.REBUILT, RecoveryStep.RESUMING);
        if (!requestsLost) {
            this.requestsLost = true;
            this.updatedAt = now;
        }
    }

    /**
     * Redis 차단을 마쳤다(그 뒤 신규 접수·재발행 배치 전송이 시작되지 않는다). 차단 시각을 이 시각으로 쓴다. 경계 재발행이 실패했을 때도
     * 부른다(시간 초과로 돌아온 전송은 아직 진행 중일 수 있다).
     */
    public void markBlocked(Instant now) {
        requireBoundaryAllowed();
        this.blockedAt = now;
        this.updatedAt = now;
    }

    /**
     * 진행 중이던 생산자 전송이 모두 끝났는가. 차단 시각에서 {@code producerSettleTime}(전송 준비 대기 + 전송 수명 + 여유)이 지나야 한다.
     * 지나기 전에 Kafka 끝 offset을 경계로 잡으면 그 뒤에 도착하는 차단 전 접수가 경계 밖에 남는다.
     */
    public void requireProducersSettled(Instant now, Duration producerSettleTime) {
        requireBoundaryAllowed();
        if (blockedAt == null) {
            throw new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION,
                    "접수 차단 시각이 기록되지 않았습니다. 복구 시작을 다시 요청하세요.");
        }
        Instant settledAt = blockedAt.plus(producerSettleTime);
        if (now.isBefore(settledAt)) {
            long remainingMillis = Duration.between(now, settledAt).toMillis();
            throw new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION,
                    "진행 중인 발급 요청 전송이 끝나기를 기다리는 중입니다. " + remainingMillis + "ms 뒤에 다시 요청하세요.");
        }
    }

    /** 경계 확정을 해도 되는가(시작 뒤이거나 경계를 다시 정할 때) */
    public void requireBoundaryAllowed() {
        requireStep("경계 확정", RecoveryStep.BLOCKED, RecoveryStep.BOUNDARY_FIXED);
    }

    /** 복구 2단계 결과를 기록한다 */
    public void recordBoundary(RecoveryBoundary boundary, Instant now) {
        requireBoundaryAllowed();
        this.republishedCount = boundary.republishedCount();
        this.republishedAt = now;
        this.kafkaBoundary = boundary.kafkaBoundary();
        this.dltBoundary = boundary.dltBoundary();
        this.requestsLost = requestsLost || boundary.requestsLost();
        this.unresolvedCount = null;
        this.unresolvedDetail = null;
        this.step = RecoveryStep.BOUNDARY_FIXED;
        this.updatedAt = now;
    }

    /** 결과 확정을 해도 되는가(경계 확정 뒤, 재구축 선점 전) */
    public void requireResultsAllowed() {
        requireStep("결과 확정", RecoveryStep.BOUNDARY_FIXED, RecoveryStep.RESULTS_CHECKED);
    }

    /** 복구 3단계: 경계까지의 미처리를 기록한다. 0이 아니어도 기록하고, 재구축이 그것을 보고 거절한다 */
    public void recordResults(RecoveryUnresolved unresolved, Instant now) {
        requireResultsAllowed();
        this.unresolvedCount = unresolved.total();
        this.unresolvedDetail = unresolved.describe();
        this.step = RecoveryStep.RESULTS_CHECKED;
        this.updatedAt = now;
    }

    /** 재구축을 해도 되는가. 결과 확정 뒤(또는 재구축을 다시 할 때)이고 기록된 미처리가 0이어야 한다. 재개를 선점한 뒤에는 하지 않는다 */
    public void requireRebuildable() {
        requireStep("재구축", RecoveryStep.RESULTS_CHECKED, RecoveryStep.REBUILDING, RecoveryStep.REBUILT);
        if (unresolvedCount == null || unresolvedCount != 0) {
            throw new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION,
                    "미처리 요청이 남아 재구축할 수 없습니다(" + unresolvedDetail + "). 정리한 뒤 결과 확정을 다시 실행하세요.");
        }
    }

    /**
     * 재구축을 선점한다(REBUILDING). Redis를 바꾸기 전에 커밋한다. 진행 중인 선점이 있으면(선점 뒤 {@code claimLease}가 지나지 않았다)
     * 거절한다. lease가 지난 선점은 버려진 것(요청 스레드가 죽었다)으로 보고 새로 선점한다.
     *
     * @return 이 선점의 토큰과 선점 전 단계. 완료·되돌림에 넘긴다
     */
    public StepClaim claimRebuild(Instant now, Duration claimLease) {
        requireRebuildable();
        return claim(RecoveryStep.REBUILDING, now, claimLease, "재구축");
    }

    /** 복구 4단계를 마쳤다(REBUILT). 그 재구축 선점이 아직 유효해야 한다 */
    public void markRebuilt(StepClaim claim, Instant now) {
        requireClaim(claim, RecoveryStep.REBUILDING);
        this.step = RecoveryStep.REBUILT;
        this.claimedAt = null;
        this.updatedAt = now;
    }

    /** 재개를 해도 되는가. 재구축 뒤(또는 재개를 다시 할 때)이고 재개 근거가 있어야 한다 */
    public void requireResumable(String basis) {
        requireStep("재개", RecoveryStep.REBUILT, RecoveryStep.RESUMING);
        if (basis == null || basis.isBlank()) {
            throw new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION, "재개 근거가 필요합니다.");
        }
    }

    /**
     * 재개를 선점한다(RESUMING). Redis 접수 차단을 풀기 전에 커밋해, 같은 복구의 재구축이 그 사이 Redis를 다시 만들지 못하게 한다(재구축은
     * RESUMING에서 하지 않는다). 진행 중인 선점이 있으면 거절하고, lease가 지난 선점은 새로 선점한다.
     *
     * @return 이 선점의 토큰과 선점 전 단계. 완료·되돌림에 넘긴다
     */
    public StepClaim claimResume(String basis, Instant now, Duration claimLease) {
        requireResumable(basis);
        return claim(RecoveryStep.RESUMING, now, claimLease, "재개");
    }

    /** 복구 5단계를 마쳤다(RESUMED, 끝). 그 재개 선점이 아직 유효해야 한다 */
    public void markResumed(StepClaim claim, String basis, String evidence, Instant now) {
        requireClaim(claim, RecoveryStep.RESUMING);
        requireResumable(basis);
        this.resumeBasis = basis;
        this.resumeEvidence = evidence;
        this.step = RecoveryStep.RESUMED;
        this.claimedAt = null;
        this.resumedAt = now;
        this.updatedAt = now;
    }

    /**
     * Redis가 아무것도 쓰지 않았다고 확실히 답한 재구축·재개 실패. 선점 전 단계로 되돌린다. 선점 전이 선점 단계 자신(REBUILDING·RESUMING)이면
     * 그 단계에 남고 선점만 푼다(앞선 시도의 결과를 여전히 모른다).
     */
    public void releaseClaim(StepClaim claim, Instant now) {
        requireClaim(claim, claim.step());
        this.step = claim.from();
        this.claimedAt = null;
        this.updatedAt = now;
    }

    /**
     * 결과를 알 수 없는 재구축·재개 실패(Redis에 닿지 못했거나 응답을 못 받았다). 선점 단계(REBUILDING·RESUMING)에 남기고 선점만 풀어 같은
     * 작업만 다시 하게 한다.
     */
    public void abandonClaim(StepClaim claim, Instant now) {
        requireClaim(claim, claim.step());
        this.claimedAt = null;
        this.updatedAt = now;
    }

    private StepClaim claim(RecoveryStep claimStep, Instant now, Duration claimLease, String action) {
        if (claimedAt != null && now.isBefore(claimedAt.plus(claimLease))) {
            throw new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION,
                    "같은 복구의 " + action + "(또는 다른 단계 작업)가 이미 진행 중입니다.");
        }
        StepClaim claim = new StepClaim(claimStep, now, step);
        this.step = claimStep;
        this.claimedAt = now;
        this.updatedAt = now;
        return claim;
    }

    private void requireClaim(StepClaim claim, RecoveryStep claimStep) {
        if (claim.step() != claimStep || step != claimStep || !claim.claimedAt().equals(claimedAt)) {
            throw new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION,
                    "선점이 유효하지 않습니다(지금 단계 " + step + ").");
        }
    }

    /**
     * 재구축 전에 복구를 취소한다(ABORTED, 끝). 접수 차단(hold)은 그대로 두고 새 복구로 이어 간다. 이미 취소했으면 첫 취소 기록(취소한 관리자·
     * 시각)을 그대로 두고 아무것도 바꾸지 않는다(응답을 받지 못한 재시도).
     */
    public void abort(long adminId, Instant now) {
        if (step == RecoveryStep.ABORTED) {
            return;
        }
        if (step == RecoveryStep.REBUILDING || step == RecoveryStep.REBUILT || step == RecoveryStep.RESUMING) {
            throw new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION,
                    "재구축한(또는 재구축·재개를 시작한) 복구는 취소할 수 없습니다. 재구축을 마치고 재개로 끝내세요"
                            + "(재개 결과를 모르는 채 Redis 기록이 사라진 경우만 예외로 취소한다).");
        }
        requireStep("취소", RecoveryStep.BLOCKED, RecoveryStep.BOUNDARY_FIXED, RecoveryStep.RESULTS_CHECKED);
        this.step = RecoveryStep.ABORTED;
        this.abortedBy = adminId;
        this.abortedAt = now;
        this.updatedAt = now;
    }

    /**
     * 재개 결과를 알 수 없는 복구(RESUMING)를 Redis 유실이 확인돼 취소한다(ABORTED, 끝). 진행 중인 재개 선점이 없고({@code claimLease}가
     * 지났거나 선점이 풀렸다) Redis issue-control이 없거나 runId가 바뀌었을 때만 한다. 그 사이 접수가 열렸을 수 있고 그 기록은 사라졌으므로
     * 유실 의심으로 남기고(새 복구가 이어받는다) 사유를 기록한다. 접수 차단(hold)은 풀지 않는다.
     */
    public void abortLostResume(long adminId, RecoveryRedisState redisState, Instant now, Duration claimLease) {
        requireLostResumeAbortable(now, claimLease);
        if (!redisState.isLost()) {
            throw new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION,
                    "재구축한 Redis 기록이 그대로 있어 취소할 수 없습니다. 재개를 다시 요청하세요.");
        }
        this.step = RecoveryStep.ABORTED;
        this.claimedAt = null;
        this.requestsLost = true;
        this.abortReason = "RESUME_UNKNOWN_" + redisState.name();
        this.abortedBy = adminId;
        this.abortedAt = now;
        this.updatedAt = now;
    }

    /** 재개 중 유실 취소의 Redis 확인 전 조건: RESUMING이고 진행 중인 재개 선점이 없다 */
    public void requireLostResumeAbortable(Instant now, Duration claimLease) {
        requireStep("재개 중 유실 취소", RecoveryStep.RESUMING);
        if (claimedAt != null && now.isBefore(claimedAt.plus(claimLease))) {
            throw new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION,
                    "같은 복구의 재개가 진행 중이라 취소할 수 없습니다.");
        }
    }

    public boolean isActive() {
        return step.isActive();
    }

    /**
     * 이 복구 때문에 그 쿠폰의 "Redis·DB 어디에도 없는 요청"을 지금 UNKNOWN으로 답해야 하는가. 접수 기록 유실이 의심된 복구만 해당한다.
     * <ul>
     *     <li>재개 전(진행 중이거나 취소됨): UNKNOWN. 재구축한 Redis에는 결과가 증명된 요청만 있고, 취소된 복구는 아직 아무것도 증명하지 않았다.</li>
     *     <li>재개 뒤: 그 쿠폰의 Redis 기록 보관 기간({@code recordsRetainedUntil}, 쿠폰별 key가 만료돼 기록이 정리되는 시각)까지 UNKNOWN,
     *     그 뒤에는 아니다(NOT_FOUND). 유실되지 않은 접수 기록도 그때 정리되므로, 그 뒤에는 유실 여부와 관계없이 "기록 없음"이 같은 뜻이다.
     *     보관 기간을 알 수 없으면({@code null}) UNKNOWN으로 남긴다.</li>
     * </ul>
     *
     * @param recordsRetainedUntil 그 쿠폰의 Redis 기록 보관 기간 끝. {@link #recordsRetainedUntil}로 정한다
     */
    public boolean leavesUnknownAt(Instant now, Instant recordsRetainedUntil) {
        if (!requestsLost) {
            return false;
        }
        if (step != RecoveryStep.RESUMED || recordsRetainedUntil == null) {
            return true;
        }
        return now.isBefore(recordsRetainedUntil);
    }

    /**
     * 쿠폰의 Redis 기록 보관 기간 끝: 쿠폰별 key의 만료 시각. issue-control에 남은 keysExpireAt(만료를 연장했으면 그 값)이 있으면 그것이고,
     * issue-control이 없으면(유실·만료) 발급 종료 + key 만료 여유로 계산한다. 둘 다 모르면 {@code null}이다.
     *
     * @param controlKeysExpireAt issue-control의 keysExpireAt. 없으면 null
     * @param issueEndAt          DB 쿠폰의 발급 종료 시각. 쿠폰이 없으면 null
     * @param keysExpireMargin    쿠폰별 key를 발급 종료 뒤 남기는 기간({@code hapbang.coupon.redis.keys-expire-margin})
     */
    public static Instant recordsRetainedUntil(Instant controlKeysExpireAt, Instant issueEndAt,
                                               Duration keysExpireMargin) {
        if (controlKeysExpireAt != null) {
            return controlKeysExpireAt;
        }
        return issueEndAt == null ? null : issueEndAt.plus(keysExpireMargin);
    }

    /**
     * 재구축·재개 선점.
     *
     * @param step      선점한 단계(REBUILDING·RESUMING)
     * @param claimedAt 선점 시각(토큰). 그 사이 다른 선점이 생기면 이 토큰으로는 완료·되돌림을 하지 못한다
     * @param from      선점 전 단계(되돌릴 곳)
     */
    public record StepClaim(RecoveryStep step, Instant claimedAt, RecoveryStep from) {
    }

    private void requireStep(String action, RecoveryStep... allowed) {
        if (!Set.of(allowed).contains(step)) {
            throw new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION,
                    "지금 복구 단계(" + step + ")에서는 " + action + " 단계를 실행할 수 없습니다.");
        }
    }
}
