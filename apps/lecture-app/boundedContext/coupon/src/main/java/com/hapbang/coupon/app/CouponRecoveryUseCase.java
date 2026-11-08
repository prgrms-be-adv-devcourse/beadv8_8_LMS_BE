package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.app.dto.RecoveryRunView;
import com.hapbang.coupon.app.port.CouponIssueBoundaryProbe;
import com.hapbang.coupon.app.port.CouponIssueBoundaryUnavailableException;
import com.hapbang.coupon.app.port.CouponIssueEventPublisher;
import com.hapbang.coupon.app.port.CouponIssueEventPublisher.PublishResult;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.app.port.CouponRecoveryStore;
import com.hapbang.coupon.app.port.CouponRecoveryStore.ActivateResult;
import com.hapbang.coupon.app.port.CouponRecoveryStore.BlockResult;
import com.hapbang.coupon.app.port.CouponRecoveryStore.RebuildControl;
import com.hapbang.coupon.app.port.CouponRecoveryStore.RebuildResult;
import com.hapbang.coupon.app.port.CouponRecoveryStore.Snapshot;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.config.CouponIssueDeliveryProperties;
import com.hapbang.coupon.config.CouponRecoveryProperties;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponDomainException;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.IssueRequestRecord;
import com.hapbang.coupon.domain.RecoveryBoundary;
import com.hapbang.coupon.domain.RecoveryOffsets;
import com.hapbang.coupon.domain.RecoveryQuantityCheck;
import com.hapbang.coupon.domain.RecoveryRebuildPlan;
import com.hapbang.coupon.domain.RecoveryRedisState;
import com.hapbang.coupon.domain.RecoveryRun;
import com.hapbang.coupon.domain.RecoveryRun.StepClaim;
import com.hapbang.coupon.domain.RecoveryStep;
import com.hapbang.coupon.domain.RecoveryUnresolved;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.CouponDltLogRepository;
import com.hapbang.coupon.out.RecoveryLookupRepository;
import com.hapbang.coupon.out.RecoveryRunRepository;
import com.hapbang.coupon.out.redis.CouponRedisProperties;
import com.hapbang.shared.coupon.event.CouponIssueRequested;

import lombok.extern.slf4j.Slf4j;

/**
 * Redis 유실 복구 절차(operations.md "복구와 대사" 1~5)를 관리자 요청 단계로 실행한다. 단계 순서는 {@link RecoveryRun}이 지킨다.
 * Redis·Kafka 호출은 DB 트랜잭션 밖에서 하고, 단계 결과는 짧은 트랜잭션으로 recovery_run에 남긴다. 어느 단계든 실패하면 다시 요청하면 된다
 * (같은 단계를 다시 실행해도 안전하다).
 * <ol>
 *     <li>{@link #start} 차단: 복구 실행·새 epoch 후보를 기록하고 issue-control을 ready=0·hold=RECOVERY·recoveryId=복구 ID로 막은 뒤 차단 시각을
 *     남긴다. 끝나지 않은 복구가 있으면 새로 시작하지 않는다(시작 단계에 머문 복구면 차단만 다시 하고 그 복구를 돌려준다).</li>
 *     <li>{@link #fixBoundary} 경계 확정: 차단 시각에서 생산자 전송 최대 시간 + 여유가 지나야 한다(아니면 409와 남은 시간). 차단 뒤에는 새 접수가
 *     없고, 재발행 배치는 ready≠1이면 건너뛰고, DLT 재발행은 끝나지 않은 복구가 있으면 거절되므로, 그 시각 뒤의 끝 offset이 경계다(진행 중이던
 *     전송은 모두 끝났다). 남은 requests 중 DB 결과가 없는 요청을 같은 ID·접수 epoch(옛 형식 기록이면 이전 epoch)로 모두 재발행하고(보상 표식·
 *     처리되지 않은 DLT(PENDING·REPLAYING) 제외), 그 뒤 발급 요청 토픽·DLT의 끝 offset을 경계로 기록한다. 재발행이 한 건이라도 실패하면 경계를 기록하지 않고 차단 시각을
 *     지금으로 다시 잡는다(시간 초과한 재발행이 아직 진행 중일 수 있다).</li>
 *     <li>{@link #confirmResults} 결과 확정: 미처리(재발행 대기·경계까지의 Kafka lag·처리되지 않은 DLT(PENDING·REPLAYING))를 세어 기록한다.
 *     재구축을 선점한 뒤에는 하지 않는다.</li>
 *     <li>{@link #rebuild} 재구축: 기록된 미처리가 0이고 지금 다시 세어도 0이면, 먼저 DB에서 재구축을 선점하고(REBUILDING 커밋, 낙관적 락) 그 뒤
 *     DB 확정 결과로 Redis를 새 epoch로 한 번에 다시 만든다(접수는 막힌 채). Lua도 issue-control의 recoveryId·ready=0을 확인해 재개 뒤 늦게 온
 *     재구축을 거절한다.</li>
 *     <li>{@link #resume} 재개: 먼저 DB에서 재개를 선점하고(RESUMING 커밋, 낙관적 락 — 재구축과 같은 선점이라 그 사이 재구축이 Redis를 다시
 *     만들지 못한다) 수량 대사가 맞으면 DB 쿠폰 epoch를 올리고(version도 오른다) 그 version을 fence로 접수 차단을 푼다. Lua도 issue-control의
 *     recoveryId가 이 복구일 때만 연다.</li>
 * </ol>
 * 증명할 수 없는 접수(유실된 requests)는 UNKNOWN으로 남고, 재개는 관리자가 근거를 적어 요청할 때만 한다(자동으로 재개하지 않는다).
 * <p>
 * 재구축 전에는 {@link #abort}로 복구를 취소할 수 있다. 접수 차단(hold=RECOVERY)은 풀지 않으며, 새 복구를 시작해 이어 간다. 유실 의심인 채
 * 취소된 복구 다음의 새 복구는 유실 의심을 이어받는다.
 */
@Slf4j
@Service
public class CouponRecoveryUseCase {

    /** DB 결과 존재를 한 번에 확인하는 요청 ID 수 */
    private static final int LOOKUP_CHUNK = 500;

    private final CouponSupport couponSupport;
    private final RecoveryRunRepository recoveryRunRepository;
    private final RecoveryLookupRepository recoveryLookupRepository;
    private final CouponDltLogRepository couponDltLogRepository;
    private final CouponRecoveryStore recoveryStore;
    private final CouponIssueEventPublisher eventPublisher;
    private final CouponIssueBoundaryProbe boundaryProbe;
    private final CouponRedisProperties redisProperties;
    private final CouponRecoveryProperties recoveryProperties;
    private final CouponIssueDeliveryProperties deliveryProperties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CouponRecoveryUseCase(CouponSupport couponSupport, RecoveryRunRepository recoveryRunRepository,
                                 RecoveryLookupRepository recoveryLookupRepository,
                                 CouponDltLogRepository couponDltLogRepository, CouponRecoveryStore recoveryStore,
                                 CouponIssueEventPublisher eventPublisher, CouponIssueBoundaryProbe boundaryProbe,
                                 CouponRedisProperties redisProperties, CouponRecoveryProperties recoveryProperties,
                                 CouponIssueDeliveryProperties deliveryProperties,
                                 PlatformTransactionManager transactionManager,
                                 @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponSupport = couponSupport;
        this.recoveryRunRepository = recoveryRunRepository;
        this.recoveryLookupRepository = recoveryLookupRepository;
        this.couponDltLogRepository = couponDltLogRepository;
        this.recoveryStore = recoveryStore;
        this.eventPublisher = eventPublisher;
        this.boundaryProbe = boundaryProbe;
        this.redisProperties = redisProperties;
        this.recoveryProperties = recoveryProperties;
        this.deliveryProperties = deliveryProperties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 쿠폰의 복구 실행들. 최근 것부터 */
    public List<RecoveryRunView> list(long couponId) {
        couponSupport.findCoupon(couponId);
        return recoveryRunRepository.findByCouponIdOrderByIdDesc(couponId).stream().map(RecoveryRunView::from).toList();
    }

    /** 1단계 차단 */
    public RecoveryRunView start(long adminId, long couponId) {
        Coupon coupon = couponSupport.findCoupon(couponId);
        if (!coupon.isRedisReady() || (coupon.getStatus() != CouponStatus.OPEN
                && coupon.getStatus() != CouponStatus.SUSPENDED && coupon.getStatus() != CouponStatus.CLOSED)) {
            throw new CouponException(CouponErrorCode.INVALID_COUPON_STATUS, "발급을 연 적이 있는 쿠폰만 복구합니다.");
        }
        RecoveryRun run = recoveryRunRepository.findFirstByCouponIdAndStepNotIn(couponId, RecoveryStep.finished())
                .map(active -> requireStillBlocking(active))
                .orElseGet(() -> saveNewRun(adminId, coupon));
        BlockResult block = recoveryStore.block(couponId, run.getId());
        requireNotStale(couponId, run.getId(), block);
        boolean lost = !block.controlPresent() || block.processChanged();
        // 차단 시각은 Redis 차단을 마친 뒤의 시각이다. 이미 막혀 있던 복구를 다시 막은 것이면 처음 차단 시각을 그대로 둔다
        boolean restartClock = run.getBlockedAt() == null || block.acceptsWereOpen();
        if (lost || restartClock) {
            run = inTransaction(run.getId(), current -> {
                Instant now = clock.instant();
                if (lost) {
                    current.markRequestsLost(now);
                }
                if (restartClock) {
                    current.markBlocked(now);
                }
            });
        }
        log.info("쿠폰 발급 복구를 시작해 신규 접수를 막았다 couponId={}, recoveryId={}, previousEpoch={}, newEpoch={}, "
                        + "issueControlPresent={}, processChanged={}, acceptsWereOpen={}, blockedAt={}, adminId={}",
                couponId, run.getId(), run.getPreviousEpoch(), run.getNewEpoch(), block.controlPresent(),
                block.processChanged(), block.acceptsWereOpen(), run.getBlockedAt(), adminId);
        return RecoveryRunView.from(run);
    }

    /**
     * 재구축 전 복구 취소. run을 ABORTED로 남기고 Redis는 건드리지 않는다(hold=RECOVERY·ready=0 그대로라 접수는 계속 막힌다).
     * 이미 취소한 복구면 그대로 돌려준다(재시도 멱등, 첫 취소 기록 유지). 재구축 뒤·재개 뒤에는 409(COUPON_RECOVERY_STEP_NOT_ALLOWED)이고
     * 재구축 뒤에는 재개로만 끝낸다.
     * <p>
     * 예외: 재개 결과를 모르는 복구(RESUMING)는 진행 중인 재개 선점이 없고 Redis issue-control이 없거나 runId가 바뀐 것이 지금 확인될 때만
     * 취소한다({@link RecoveryRun#abortLostResume}). 유실 의심·사유를 남기고, 새 복구가 유실 의심을 이어받아 처음부터 한다. Redis는 쓰지 않는다
     * (hold=RECOVERY가 남아 있으면 그대로 접수를 막는다).
     */
    public RecoveryRunView abort(long adminId, long couponId, long recoveryId) {
        RecoveryRun run = findRun(couponId, recoveryId);
        if (run.getStep() == RecoveryStep.ABORTED) {
            log.info("이미 취소한 쿠폰 발급 복구라 그대로 돌려준다 couponId={}, recoveryId={}, abortedBy={}, adminId={}", couponId,
                    recoveryId, run.getAbortedBy(), adminId);
            return RecoveryRunView.from(run);
        }
        if (run.getStep() == RecoveryStep.RESUMING) {
            return abortLostResume(adminId, couponId, run);
        }
        RecoveryRun updated = inTransaction(run.getId(), current -> current.abort(adminId, clock.instant()));
        log.info("쿠폰 발급 복구를 취소했다. 신규 접수 차단은 유지된다 couponId={}, recoveryId={}, previousStep={}, "
                        + "requestsLost={}, adminId={}", couponId, recoveryId, run.getStep(), updated.isRequestsLost(),
                adminId);
        return RecoveryRunView.from(updated);
    }

    /**
     * RESUMING 복구의 예외 취소. 진행 중인 재개가 없는지 먼저 보고, Redis 유실을 읽어 확인한 뒤, DB를 바꾸기 전에 이 복구 ID로 접수를 다시 막는다
     * (앞선 재개가 실제로 열었고 Redis 재시작으로 ready=1이 되살아났어도 열린 채 남지 않게). 차단 결과로 유실을 다시 확인하고 취소한다.
     * 차단을 확인하지 못하면(Redis 장애) 503으로 취소하지 않는다. 그 사이 재개가 선점하면(낙관적 락 충돌) 409다.
     */
    private RecoveryRunView abortLostResume(long adminId, long couponId, RecoveryRun run) {
        long recoveryId = run.getId();
        checkStep(() -> run.requireLostResumeAbortable(clock.instant(), recoveryProperties.claimLease()));
        RecoveryRedisState redisState = recoveryStore.redisState(couponId);
        if (redisState.isLost()) {
            BlockResult block = recoveryStore.block(couponId, recoveryId);
            requireNotStale(couponId, recoveryId, block);
            redisState = !block.controlPresent() ? RecoveryRedisState.CONTROL_MISSING
                    : block.processChanged() ? RecoveryRedisState.PROCESS_CHANGED : RecoveryRedisState.INTACT;
        }
        RecoveryRedisState confirmed = redisState;
        RecoveryRun updated;
        try {
            updated = inTransaction(recoveryId, current -> current.abortLostResume(adminId, confirmed, clock.instant(),
                    recoveryProperties.claimLease()));
        } catch (OptimisticLockingFailureException exception) {
            throw new CouponException(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED,
                    "같은 복구의 재개와 취소가 동시에 요청됐습니다.", exception);
        }
        log.warn("재개 결과를 모르는 쿠폰 발급 복구를 Redis 유실이 확인돼 취소했다. 새 복구는 유실 의심을 이어받는다 couponId={}, "
                + "recoveryId={}, redisState={}, abortReason={}, adminId={}", couponId, recoveryId, confirmed,
                updated.getAbortReason(), adminId);
        return RecoveryRunView.from(updated);
    }

    /** 2단계 경계 확정 */
    public RecoveryRunView fixBoundary(long adminId, long couponId, long recoveryId) {
        RecoveryRun run = findRun(couponId, recoveryId);
        checkStep(run::requireBoundaryAllowed);
        Coupon coupon = couponSupport.findCoupon(couponId);
        // 시작 뒤 누가 issue-control을 다시 만들었을 수 있으므로 차단을 다시 쓴다(같은 값이라 안전하다)
        BlockResult block = recoveryStore.block(couponId, recoveryId);
        requireNotStale(couponId, recoveryId, block);
        if (block.acceptsWereOpen()) {
            // 방금까지 접수가 열려 있었다. 차단 시각을 지금으로 다시 잡고 그만큼 기다리게 한다
            inTransaction(recoveryId, current -> current.markBlocked(clock.instant()));
            log.warn("복구 경계 확정 중 접수가 다시 열려 있어 차단을 다시 걸었다 couponId={}, recoveryId={}, adminId={}", couponId,
                    recoveryId, adminId);
            throw new CouponException(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED,
                    "발급 접수가 다시 열려 있어 차단을 다시 걸었습니다. " + producerSettleTime().toMillis()
                            + "ms 뒤에 경계 확정을 다시 요청하세요.");
        }
        checkStep(() -> run.requireProducersSettled(clock.instant(), producerSettleTime()));
        Snapshot snapshot = recoveryStore.snapshot(couponId);
        boolean requestsLost = !block.controlPresent() || block.processChanged() || !snapshot.controlPresent()
                || snapshot.processChanged() || snapshot.stock() == null;

        Map<String, IssueRequestRecord> pending = pendingRequests(couponId, snapshot).records();
        // 처리되지 않은 DLT(PENDING·재발행 선점 중인 REPLAYING)는 관리자 재발행·거절이 맡으므로 여기서 다시 보내지 않는다
        Set<String> dltPending = new HashSet<>(couponDltLogRepository.findUnresolvedRequestIds(couponId));
        int republished = 0;
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, IssueRequestRecord> entry : pending.entrySet()) {
            if (dltPending.contains(entry.getKey())) {
                continue;
            }
            IssueRequestRecord record = entry.getValue();
            // 접수 기록의 epoch를 싣는다. 옛 형식 기록(epoch 없음)이면 지금 쿠폰 epoch다(재발행 배치와 같은 규칙)
            CouponIssueRequested event = new CouponIssueRequested(entry.getKey(), couponId, record.memberId(),
                    record.epochOr(coupon.getEpoch()), record.acceptedAt(), record.expiresAt());
            PublishResult result = eventPublisher.publish(event, recoveryProperties.republishAckTimeout());
            if (result.acked()) {
                republished++;
            } else {
                failed.add(entry.getKey());
            }
        }
        if (!failed.isEmpty()) {
            // 시간 초과로 돌아온 재발행은 아직 전송 중일 수 있다. 다음 경계 확정은 그것이 끝난 뒤에 한다
            inTransaction(recoveryId, current -> current.markBlocked(clock.instant()));
            log.error("복구 경계 재발행이 실패해 경계를 기록하지 않았다 couponId={}, recoveryId={}, failed={}, republished={}",
                    couponId, recoveryId, failed.size(), republished);
            throw new CouponException(CouponErrorCode.COUPON_EVENT_PUBLISH_FAILED,
                    "발급 요청 " + failed.size() + "건을 재발행하지 못했습니다. Kafka 상태를 확인한 뒤 다시 시도해 주세요.");
        }
        RecoveryOffsets eventBoundary = kafka(boundaryProbe::eventEndOffsets);
        RecoveryOffsets dltBoundary = kafka(boundaryProbe::dltEndOffsets);
        RecoveryBoundary boundary = new RecoveryBoundary(republished, requestsLost, eventBoundary.format(),
                dltBoundary.format());
        RecoveryRun updated = inTransaction(recoveryId, current -> current.recordBoundary(boundary, clock.instant()));
        log.info("쿠폰 발급 복구 경계를 확정했다 couponId={}, recoveryId={}, republished={}, skippedDltPending={}, "
                        + "requestsLost={}, kafkaBoundary={}, dltBoundary={}, adminId={}", couponId, recoveryId, republished,
                pending.keySet().stream().filter(dltPending::contains).count(), updated.isRequestsLost(),
                boundary.kafkaBoundary(), boundary.dltBoundary(), adminId);
        return RecoveryRunView.from(updated);
    }

    /** 3단계 결과 확정 */
    public RecoveryRunView confirmResults(long adminId, long couponId, long recoveryId) {
        RecoveryRun run = findRun(couponId, recoveryId);
        checkStep(run::requireResultsAllowed);
        RecoveryUnresolved unresolved = measureUnresolved(couponId, run);
        RecoveryRun updated = inTransaction(recoveryId, current -> current.recordResults(unresolved, clock.instant()));
        log.info("쿠폰 발급 복구 결과를 확인했다 couponId={}, recoveryId={}, unresolved={}, adminId={}", couponId, recoveryId,
                unresolved.describe(), adminId);
        return RecoveryRunView.from(updated);
    }

    /** 4단계 재구축 */
    public RecoveryRunView rebuild(long adminId, long couponId, long recoveryId) {
        RecoveryRun run = findRun(couponId, recoveryId);
        checkStep(run::requireRebuildable);
        // 결과 확정 뒤 시간이 지났으므로 다시 센다. 남아 있으면 기록하고 거절한다
        RecoveryUnresolved unresolved = measureUnresolved(couponId, run);
        if (!unresolved.isEmpty()) {
            // 재구축을 선점한 뒤(REBUILDING·REBUILT)에는 결과 확정 단계로 되돌리지 않는다(취소 금지를 우회하지 않게)
            if (run.getStep() == RecoveryStep.RESULTS_CHECKED) {
                inTransaction(recoveryId, current -> current.recordResults(unresolved, clock.instant()));
            }
            throw new CouponException(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED,
                    "미처리 요청이 남아 재구축할 수 없습니다(" + unresolved.describe() + ").");
        }
        Coupon coupon = couponSupport.findCoupon(couponId);
        Instant now = clock.instant();
        Instant keysExpireAt = redisProperties.keysExpireAt(coupon.getIssueEndAt());
        if (!keysExpireAt.isAfter(now)) {
            throw new CouponException(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED,
                    "발급 기록 보관 기간이 지난 쿠폰은 재구축하지 않습니다.");
        }
        RecoveryRebuildPlan plan = stepRule(() -> RecoveryRebuildPlan.of(coupon, run.getNewEpoch(),
                recoveryLookupRepository.findResults(couponId)));
        RebuildControl control = new RebuildControl(coupon.getIssueStartAt(), coupon.getIssueEndAt(), keysExpireAt,
                coupon.getVersion(), coupon.getExpiryType(), coupon.getExpiryDays(), coupon.getFixedExpiresAt(),
                coupon.getStatus().name(), now);
        StepClaim claim = claim(recoveryId, current -> current.claimRebuild(clock.instant(),
                recoveryProperties.claimLease()), "재구축");
        RebuildResult result;
        try {
            result = recoveryStore.rebuild(couponId, recoveryId, plan, control);
        } catch (RuntimeException exception) {
            // Redis에 닿지 못했거나 응답을 못 받았다. 썼는지 알 수 없으므로 REBUILDING에 남겨 재구축만 다시 하게 한다
            finishClaim(recoveryId, current -> current.abandonClaim(claim, clock.instant()));
            log.error("쿠폰 발급 Redis 재구축 결과를 알 수 없어 재구축 단계에 남겼다 couponId={}, recoveryId={}, adminId={}",
                    couponId, recoveryId, adminId, exception);
            throw exception;
        }
        if (result != RebuildResult.APPLIED) {
            // Redis가 아무것도 쓰지 않았다. 선점 전 단계로 되돌린다
            finishClaim(recoveryId, current -> current.releaseClaim(claim, clock.instant()));
            log.error("쿠폰 발급 Redis 재구축이 거절돼 아무것도 쓰지 않았다 couponId={}, recoveryId={}, result={}, adminId={}",
                    couponId, recoveryId, result, adminId);
            if (result == RebuildResult.FENCED) {
                throw new CouponException(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED,
                        "Redis 발급 상태가 이 복구의 차단이 아닙니다(재개됐거나 다른 복구가 막았습니다). 복구 상태를 확인하세요.");
            }
            throw new CouponIssueStoreUnavailableException("복구 재구축을 쓰지 못했다 couponId=" + couponId, null);
        }
        RecoveryRun updated = inTransaction(recoveryId, current -> current.markRebuilt(claim, clock.instant()));
        log.info("쿠폰 발급 Redis 기록을 DB 확정 결과로 재구축했다 couponId={}, recoveryId={}, newEpoch={}, stock={}, "
                        + "issuedMembers={}, requests={}, adminId={}", couponId, recoveryId, plan.newEpoch(), plan.stock(),
                plan.memberIds().size(), plan.requests().size(), adminId);
        return RecoveryRunView.from(updated);
    }

    /**
     * 5단계 재개. 재개를 선점(RESUMING 커밋)한 뒤 Redis 상태·수량 대사를 확인하고 DB epoch를 올린 다음 Redis 접수 차단을 푼다.
     * <ul>
     *     <li>Redis를 쓰기 전에 거절되거나, Lua가 아무것도 쓰지 않았다고 답하면 선점 전 단계로 되돌린다(REBUILT면 재구축을 다시 할 수 있다).</li>
     *     <li>Lua 결과를 알 수 없으면(Redis에 닿지 못했거나 응답을 못 받았다) RESUMING에 남기고 선점만 푼다. 접수가 이미 열렸을 수 있어 재구축·
     *     취소는 하지 않고 재개만 다시 한다(Lua는 같은 epoch·recoveryId로 이미 열려 있으면 연 결과로 답한다).</li>
     * </ul>
     */
    public RecoveryRunView resume(long adminId, long couponId, long recoveryId, String basis) {
        RecoveryRun run = findRun(couponId, recoveryId);
        checkStep(() -> run.requireResumable(basis));
        StepClaim claim = claim(recoveryId, current -> current.claimResume(basis, clock.instant(),
                recoveryProperties.claimLease()), "재개");
        RecoveryQuantityCheck check;
        Coupon activated;
        try {
            check = checkResumable(couponId, recoveryId, claim);
            activated = transaction.execute(status -> activateEpoch(couponId, run));
        } catch (RuntimeException exception) {
            // Redis에는 아직 아무것도 쓰지 않았다. 선점 전 단계로 되돌린다
            finishClaim(recoveryId, current -> current.releaseClaim(claim, clock.instant()));
            throw exception;
        }
        ActivateResult result;
        try {
            result = recoveryStore.activate(couponId, recoveryId, run.getNewEpoch(), activated.getStatus().name(),
                    activated.getVersion());
        } catch (RuntimeException exception) {
            // 접수를 열었는지 알 수 없다. RESUMING에 남겨 재개만 다시 하게 한다
            finishClaim(recoveryId, current -> current.abandonClaim(claim, clock.instant()));
            log.error("쿠폰 발급 복구 재개 결과를 알 수 없어 재개 단계에 남겼다 couponId={}, recoveryId={}, adminId={}",
                    couponId, recoveryId, adminId, exception);
            throw exception;
        }
        if (!result.opened()) {
            finishClaim(recoveryId, current -> current.releaseClaim(claim, clock.instant()));
            log.error("쿠폰 발급 복구 재개가 거절돼 아무것도 쓰지 않았다 couponId={}, recoveryId={}, result={}, returnedTo={}, "
                    + "adminId={}", couponId, recoveryId, result, claim.from(), adminId);
            throw activateRejected(couponId, result, claim);
        }
        String evidence = check.describe() + ",epoch=" + run.getNewEpoch() + ",fence=" + activated.getVersion()
                + ",status=" + activated.getStatus() + ",activate=" + result;
        RecoveryRun updated = inTransaction(recoveryId,
                current -> current.markResumed(claim, basis, evidence, clock.instant()));
        log.info("쿠폰 발급 복구를 마치고 새 epoch로 재개했다 couponId={}, recoveryId={}, newEpoch={}, status={}, evidence={}, "
                        + "requestsLost={}, adminId={}", couponId, recoveryId, run.getNewEpoch(), activated.getStatus(),
                evidence, updated.isRequestsLost(), adminId);
        return RecoveryRunView.from(updated);
    }

    /** 재개 선점 뒤 Redis 재구축 상태와 수량 대사를 확인한다. 맞지 않으면 409다 */
    private RecoveryQuantityCheck checkResumable(long couponId, long recoveryId, StepClaim claim) {
        Coupon coupon = couponSupport.findCoupon(couponId);
        Snapshot snapshot = recoveryStore.snapshot(couponId);
        if (!snapshot.controlPresent() || snapshot.processChanged()) {
            throw new CouponException(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED, redisStateLostMessage(claim));
        }
        PendingRequests pending = pendingRequests(couponId, snapshot);
        RecoveryQuantityCheck check = new RecoveryQuantityCheck(coupon.getQuantity(), coupon.getIssuedCount(),
                snapshot.stock(), pending.records().size() + pending.malformed());
        if (!check.matches()) {
            log.error("복구 재개 전 수량 대사가 맞지 않아 재개하지 않았다 couponId={}, recoveryId={}, {}", couponId, recoveryId,
                    check.describe());
            throw new CouponException(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED,
                    "수량 대사가 맞지 않아 재개할 수 없습니다(" + check.describe() + ").");
        }
        return check;
    }

    /** activate Lua가 아무것도 쓰지 않은 결과를 응답 예외로 바꾼다 */
    private RuntimeException activateRejected(long couponId, ActivateResult result, StepClaim claim) {
        return switch (result) {
            case FENCED -> new CouponException(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED,
                    "Redis 발급 상태가 이 복구의 차단이 아닙니다(다른 복구가 막았습니다). 복구 상태를 확인하세요.");
            case MISSING, EPOCH_MISMATCH, PROCESS_CHANGED -> new CouponException(
                    CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED, redisStateLostMessage(claim));
            default -> new CouponIssueStoreUnavailableException(
                    "복구 재개를 쓰지 못했다 couponId=" + couponId + ", result=" + result, null);
        };
    }

    /**
     * 재구축한 Redis 기록이 없거나 바뀌었을 때의 안내. 재구축 뒤 재개를 처음 시도한 것이면 재구축부터 다시 하면 된다. 앞선 재개의 결과를 모르는
     * 채(RESUMING)라면 그 사이 접수가 열렸을 수 있어 재구축으로 덮지 않는다.
     */
    private static String redisStateLostMessage(StepClaim claim) {
        if (claim.from() == RecoveryStep.REBUILT) {
            return "재구축한 Redis 기록이 없거나 그 뒤 Redis 프로세스가 바뀌었습니다. 재구축부터 다시 실행하세요.";
        }
        return "앞선 재개의 결과를 알 수 없는데 Redis 기록이 이 복구의 재구축 상태가 아닙니다(사라졌거나 프로세스가 바뀌었습니다). "
                + "접수가 열렸을 수 있어 재구축으로 덮지 않습니다. 운영자 확인이 필요합니다.";
    }

    /**
     * 더 새 복구가 issue-control을 가졌으면(늦게 도착한 이전 복구의 차단) 이 복구는 이미 끝났다. 접수는 막혔지만 recoveryId는 그대로라 새 복구의
     * 재구축·재개 토큰은 지켜졌다. 이 복구로는 더 진행하지 않는다(409).
     */
    private static void requireNotStale(long couponId, long recoveryId, BlockResult block) {
        if (block.stale()) {
            log.error("더 새 복구가 있어 이전 복구의 차단은 접수만 막고 복구 ID를 바꾸지 않았다 couponId={}, recoveryId={}, "
                    + "acceptsWereOpen={}", couponId, recoveryId, block.acceptsWereOpen());
            throw new CouponException(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED,
                    "더 새 복구가 진행 중이거나 끝나 이 복구는 더 진행할 수 없습니다. 복구 목록을 확인하세요.");
        }
    }

    /** 접수 차단 뒤 경계를 잡기까지 기다리는 시간: 생산자 전송 최대 시간 + 여유 */
    private Duration producerSettleTime() {
        return deliveryProperties.maxSendLifetime().plus(recoveryProperties.boundaryGrace());
    }

    /**
     * 재구축·재개를 선점해 커밋한다. 같은 복구의 다른 선점이 진행 중이거나(도메인 규칙) 동시에 선점하면(낙관적 락 충돌) 409다. 한 복구에서
     * 진행 중인 선점은 하나뿐이므로 재구축과 재개가 함께 Redis를 바꾸지 않는다.
     */
    private StepClaim claim(long recoveryId, Function<RecoveryRun, StepClaim> claimer, String action) {
        AtomicReference<StepClaim> claim = new AtomicReference<>();
        try {
            inTransaction(recoveryId, current -> claim.set(claimer.apply(current)));
        } catch (OptimisticLockingFailureException exception) {
            throw new CouponException(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED,
                    "같은 복구의 " + action + "와 다른 단계 작업이 동시에 요청됐습니다.", exception);
        }
        return claim.get();
    }

    /** 실패한 재구축·재개의 선점을 정리한다. 정리하지 못해도(선점이 이미 바뀌었다) 원래 실패를 가리지 않는다 */
    private void finishClaim(long recoveryId, Consumer<RecoveryRun> change) {
        try {
            inTransaction(recoveryId, change);
        } catch (RuntimeException exception) {
            log.error("복구 선점을 정리하지 못했다 recoveryId={}", recoveryId, exception);
        }
    }

    /**
     * DB 쿠폰 epoch를 새 epoch로 올리고 version도 올린다(커밋된 version이 issue-control에 쓸 fence다). 재개를 다시 요청하면 이미 새 epoch라
     * version만 오른다. 쿠폰 epoch가 이 복구의 이전·새 epoch가 아니면 다른 변경이 있었던 것이라 거절한다.
     */
    private Coupon activateEpoch(long couponId, RecoveryRun run) {
        Coupon coupon = couponSupport.findCouponIncrementingVersion(couponId);
        if (coupon.getEpoch() != run.getPreviousEpoch() && coupon.getEpoch() != run.getNewEpoch()) {
            throw new CouponException(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED,
                    "쿠폰 epoch가 이 복구의 epoch와 다릅니다 epoch=" + coupon.getEpoch());
        }
        coupon.activateRecoveredEpoch(run.getNewEpoch(), clock.instant());
        return coupon;
    }

    /**
     * 미처리: requests에 있고 DB 결과가 없는 요청(보상 표식 제외, 읽을 수 없는 값 포함), 경계까지 소비 그룹이 커밋하지 않은 메시지,
     * 이 쿠폰의 처리되지 않은 DLT(PENDING과 재발행 선점 중인 REPLAYING — lease가 지났어도 센다). Kafka 상태를 읽지 못하면 0으로 보지 않고
     * 503이다.
     */
    private RecoveryUnresolved measureUnresolved(long couponId, RecoveryRun run) {
        PendingRequests pending = pendingRequests(couponId, recoveryStore.snapshot(couponId));
        RecoveryOffsets boundary = RecoveryOffsets.parse(run.getKafkaBoundary());
        long kafkaLag = boundary.lagFrom(kafka(boundaryProbe::committedOffsets));
        long dltPending = couponDltLogRepository.findUnresolvedRequestIds(couponId).size();
        return new RecoveryUnresolved(pending.records().size() + pending.malformed(), kafkaLag, dltPending);
    }

    /** requests 가운데 DB 결과가 없고 보상 표식도 없는 요청. 읽을 수 없는 값은 따로 센다(결과를 증명할 수 없다) */
    private PendingRequests pendingRequests(long couponId, Snapshot snapshot) {
        Map<String, IssueRequestRecord> records = new LinkedHashMap<>();
        int malformed = 0;
        for (Map.Entry<String, String> entry : snapshot.requests().entrySet()) {
            try {
                IssueRequestRecord record = IssueRequestRecord.parse(entry.getValue());
                if (!record.compensated()) {
                    records.put(entry.getKey(), record);
                }
            } catch (IllegalArgumentException exception) {
                malformed++;
                log.error("복구 중 읽을 수 없는 접수 기록을 미처리로 센다 couponId={}, issueRequestId={}", couponId,
                        entry.getKey());
            }
        }
        List<String> ids = new ArrayList<>(records.keySet());
        for (int from = 0; from < ids.size(); from += LOOKUP_CHUNK) {
            List<String> chunk = ids.subList(from, Math.min(ids.size(), from + LOOKUP_CHUNK));
            recoveryLookupRepository.findExistingRequestIds(couponId, chunk).forEach(records::remove);
        }
        return new PendingRequests(records, malformed);
    }

    private RecoveryRun findRun(long couponId, long recoveryId) {
        return recoveryRunRepository.findByIdAndCouponId(recoveryId, couponId)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_RECOVERY_NOT_FOUND));
    }

    /** 끝나지 않은 복구가 시작 단계에 머물러 있으면 그 복구를 이어 쓴다(차단만 다시 한다). 그 뒤 단계면 새로 시작하지 않는다 */
    private RecoveryRun requireStillBlocking(RecoveryRun active) {
        if (active.getStep() != RecoveryStep.BLOCKED) {
            throw new CouponException(CouponErrorCode.COUPON_RECOVERY_IN_PROGRESS,
                    "이미 진행 중인 복구가 있습니다 recoveryId=" + active.getId() + ", step=" + active.getStep());
        }
        return active;
    }

    /** 새 복구를 기록한다. 바로 앞 복구가 유실 의심인 채 취소됐으면 그 의심을 이어받는다(취소는 아무것도 증명하지 않았다) */
    private RecoveryRun saveNewRun(long adminId, Coupon coupon) {
        boolean inheritedLost = recoveryRunRepository.findFirstByCouponIdOrderByIdDesc(coupon.getId())
                .map(last -> last.getStep() == RecoveryStep.ABORTED && last.isRequestsLost())
                .orElse(false);
        try {
            return transaction.execute(status -> recoveryRunRepository.saveAndFlush(
                    RecoveryRun.start(coupon.getId(), coupon.getEpoch(), adminId, inheritedLost, clock.instant())));
        } catch (DataIntegrityViolationException exception) {
            // 같은 쿠폰의 복구를 동시에 시작했다. 끝나지 않은 복구는 하나뿐이다(uk_recovery_run_active)
            throw new CouponException(CouponErrorCode.COUPON_RECOVERY_IN_PROGRESS, exception);
        }
    }

    /** recovery_run을 다시 읽어 바꾸고 커밋한다. 도메인 단계 규칙 위반은 409(COUPON_RECOVERY_STEP_NOT_ALLOWED)다 */
    private RecoveryRun inTransaction(long recoveryId, Consumer<RecoveryRun> change) {
        return stepRule(() -> transaction.execute(status -> {
            RecoveryRun current = recoveryRunRepository.findById(recoveryId)
                    .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_RECOVERY_NOT_FOUND));
            change.accept(current);
            return current;
        }));
    }

    private static void checkStep(Runnable rule) {
        stepRule(() -> {
            rule.run();
            return null;
        });
    }

    private static <T> T stepRule(Supplier<T> call) {
        try {
            return call.get();
        } catch (CouponDomainException exception) {
            throw new CouponException(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED, exception.getMessage(), exception);
        }
    }

    private static RecoveryOffsets kafka(Supplier<RecoveryOffsets> call) {
        try {
            return call.get();
        } catch (CouponIssueBoundaryUnavailableException exception) {
            throw new CouponException(CouponErrorCode.COUPON_ISSUE_FAILED,
                    "Kafka 복구 경계를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.", exception);
        }
    }

    /**
     * @param records   DB 결과가 없고 보상 표식이 없는 요청(요청 ID → 접수 기록)
     * @param malformed 읽을 수 없는 requests 값 수
     */
    private record PendingRequests(Map<String, IssueRequestRecord> records, int malformed) {
    }
}
