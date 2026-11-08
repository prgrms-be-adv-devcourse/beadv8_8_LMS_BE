package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.app.dto.ReconcileHoldReleaseView;
import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponReconciliationStore;
import com.hapbang.coupon.app.port.CouponReconciliationStore.RepairResult;
import com.hapbang.coupon.app.port.CouponReconciliationStore.Snapshot;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.config.CouponIssueDeliveryProperties;
import com.hapbang.coupon.config.CouponLimitsProperties;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.IssueBacklogRule;
import com.hapbang.coupon.domain.IssueCapacityRule;
import com.hapbang.coupon.domain.IssueHoldReason;
import com.hapbang.coupon.domain.IssueReconciliationRule;
import com.hapbang.coupon.domain.IssueReconciliationRule.DbResult;
import com.hapbang.coupon.domain.IssueReconciliationRule.Judgment;
import com.hapbang.coupon.domain.IssueReconciliationRule.Mismatch;
import com.hapbang.coupon.domain.IssueReconciliationRule.SoldOutRepair;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.IssueCapacityReservationRepository;
import com.hapbang.coupon.out.IssueResultReadRepository;
import com.hapbang.coupon.out.IssueResultRow;
import com.hapbang.coupon.out.redis.CouponRedisProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 발급 대사·적체 차단·기록 정리. 대상은 용량 예약이 있는 쿠폰(Redis 기록이 남았을 수 있는 쿠폰)이다. 쿠폰마다 따로 처리하고, 한 쿠폰의 실패가
 * 회차를 멈추지 않는다.
 * <ol>
 *     <li>DB(쿠폰·발급 요청 결과 전부)를 한 스냅숏으로 읽은 뒤 Redis 기록을 한 번에 읽어 수량 식을 대조한다({@link IssueReconciliationRule}).
 *     맞지 않으면 한 번 더 같은 순서로 읽어 확인하고, 그래도 맞지 않을 때만 불일치로 확정한다(동시 쓰기 중의 한 번 읽기로 장애를 확정하지 않는다).</li>
 *     <li>불일치(음수 재고·DB 총량 초과·원인 불명): 그 쿠폰의 신규 접수를 보류(hold=RECONCILE)하고 지표·ERROR 로그로 알린다. 재고를 임의로
 *     INCR·DECR하지 않고 기록도 고치거나 지우지 않는다. 이 보류는 자동으로 풀지 않고, 관리자가 원인을 확인한 뒤 해제 API로 푼다
 *     ({@link #releaseReconcileHold}). 복구 절차는 보류를 RECOVERY로 덮어쓰고 재개 때 지우므로 따로 풀 필요가 없다.</li>
 *     <li>일치: DB 최종 결과가 확인된 요청 기록만 지우고(ISSUED. 접수가 끝난 쿠폰이면 REJECTED이고 보상 표식, 이전 epoch이고 DB 결과 있음도.
 *     접수가 열릴 수 있는 동안 REJECTED 기록을 남기는 까닭은 {@link IssueReconciliationRule}), 매진 표시·회원 기록을
 *     확정 발급과 진행 중 접수에 맞게 고친다. DB 결과가 없는 이전 epoch 기록은 남기고 WARN 로그로 알린다. 재발행 대기 건수로 적체 보류(BACKLOG)를 걸거나 푼다. 다시 열 수 없는 쿠폰(CLOSED·REJECTED)의 기록이 모두 정리됐으면 Redis key를
 *     지우고 용량 예약을 푼다.</li>
 *     <li>고아 예약: 발급 중(OPEN·SUSPENDED)이 아니고 Redis key가 하나도 없는 쿠폰의 예약을 푼다. 열기 전 쿠폰은 예약(또는 열기 재시도의 재사용)
 *     뒤 {@code hapbang.coupon.limits.orphan-reservation-grace}가 지난 것만 푼다.</li>
 * </ol>
 * 판정(일치·불일치 모두) 뒤 DB 결과가 없는 접수 기록(진행 중·이전 epoch 미확정)이나 읽을 수 없는 기록이 남았으면 그 쿠폰의 key 만료를
 * 지금 + {@code hapbang.coupon.redis.keys-expire-margin}까지 늦춘다({@link CouponIssueStore#extendKeysExpiry}, 줄이지 않는다). 발급 종료 +
 * 여유에 key가 만료돼 미확정 요청의 근거가 사라지지 않게 한다. 기록은 확정이 끝난 뒤에만 위 정리 규칙대로 지운다.
 * <p>
 * 복구 중(hold=RECOVERY 또는 ready≠1)인 쿠폰은 건드리지 않는다. issue-control이 없는데 발급 중이던 쿠폰은 유실 의심으로 알리기만 한다(복구 절차의 몫).
 * 대사 불일치 보류(RECONCILE)가 남은 쿠폰은 원인을 확인하기 전이라 기록 정리·고치기도 하지 않는다.
 * <p>
 * 같은 앱에서 동시에 두 번 실행하지 않는다(실행 중 표시는 앱 메모리, 강의 앱 1대 전제). replica를 늘리면 단일 실행 장치를 먼저 둔다.
 */
@Slf4j
@Service
public class CouponReconciliationUseCase {

    /** 불일치 확정 ERROR 로그의 alert 표식 */
    static final String MISMATCH_LOG_TAG = "[COUPON_ISSUE_RECONCILIATION_MISMATCH]";

    private final IssueCapacityReservationRepository reservationRepository;
    private final CouponRepository couponRepository;
    private final IssueResultReadRepository resultRepository;
    private final CouponReconciliationStore store;
    private final CouponIssueStore issueStore;
    private final CouponRedisProperties redisProperties;
    private final CouponCapacitySupport capacitySupport;
    private final CouponReconciliationMetrics metrics;
    private final CouponLimitsProperties limits;
    private final CouponIssueDeliveryProperties delivery;
    private final TransactionTemplate snapshotTransaction;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public CouponReconciliationUseCase(IssueCapacityReservationRepository reservationRepository,
                                       CouponRepository couponRepository, IssueResultReadRepository resultRepository,
                                       CouponReconciliationStore store, CouponIssueStore issueStore,
                                       CouponRedisProperties redisProperties, CouponCapacitySupport capacitySupport,
                                       CouponReconciliationMetrics metrics, CouponLimitsProperties limits,
                                       CouponIssueDeliveryProperties delivery,
                                       PlatformTransactionManager transactionManager,
                                       @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.reservationRepository = reservationRepository;
        this.couponRepository = couponRepository;
        this.resultRepository = resultRepository;
        this.store = store;
        this.issueStore = issueStore;
        this.redisProperties = redisProperties;
        this.capacitySupport = capacitySupport;
        this.metrics = metrics;
        this.limits = limits;
        this.delivery = delivery;
        // 쿠폰과 발급 요청 결과를 한 스냅숏으로 읽는다(그 사이 소비자가 커밋해도 둘이 어긋나지 않는다)
        this.snapshotTransaction = new TransactionTemplate(transactionManager);
        this.snapshotTransaction.setReadOnly(true);
        this.snapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.clock = clock;
    }

    /**
     * 배치 한 회차. 이미 실행 중이면 건너뛴다.
     *
     * @return 회차 결과. 건너뛰었으면 null
     */
    public Summary runScheduled() {
        if (!running.compareAndSet(false, true)) {
            log.info("쿠폰 발급 대사가 이미 실행 중이라 이번 배치를 건너뛴다");
            return null;
        }
        try {
            return run();
        } finally {
            running.set(false);
        }
    }

    /**
     * 관리자 대사 불일치 보류(RECONCILE) 해제. 보류가 RECONCILE일 때만 지운다. 원인이 그대로면 다음 대사 회차가 다시 보류한다.
     * <ul>
     *     <li>보류가 없으면(이미 풀었거나 issue-control이 없다) 바꾸지 않고 {@code released=false}로 응답한다(다시 보낸 요청).</li>
     *     <li>보류가 다른 사유(적체·복구)면 409({@code COUPON_RECONCILE_HOLD_NOT_RELEASABLE})이고 바꾸지 않는다. 적체는 대사 배치가, 복구는 복구
     *     재개가 푼다.</li>
     * </ul>
     * 쿠폰이 없으면 404, 발급 저장소에 닿지 못하면 503이다.
     */
    public ReconcileHoldReleaseView releaseReconcileHold(long adminId, long couponId) {
        if (!couponRepository.existsById(couponId)) {
            throw new CouponException(CouponErrorCode.COUPON_NOT_FOUND);
        }
        if (store.releaseHold(couponId, IssueHoldReason.RECONCILE)) {
            log.warn("관리자가 쿠폰 발급 대사 불일치 보류를 풀었다 couponId={}, adminId={}", couponId, adminId);
            return new ReconcileHoldReleaseView(couponId, true, null);
        }
        IssueHoldReason current = store.readHold(couponId);
        if (current != null) {
            throw new CouponException(CouponErrorCode.COUPON_RECONCILE_HOLD_NOT_RELEASABLE,
                    "대사 불일치 보류가 아니라 풀 수 없습니다. 지금 보류 사유: " + current.name());
        }
        log.info("풀 대사 불일치 보류가 없어 그대로 두었다 couponId={}, adminId={}", couponId, adminId);
        return new ReconcileHoldReleaseView(couponId, false, null);
    }

    private Summary run() {
        Tally tally = new Tally();
        for (Long couponId : reservationRepository.findCouponIds()) {
            try {
                tally.add(reconcile(couponId));
            } catch (RuntimeException exception) {
                tally.failed++;
                log.warn("쿠폰 발급 대사 중 예외로 이 쿠폰을 건너뛴다 couponId={}", couponId, exception);
            }
        }
        metrics.updateRun(tally.mismatches, tally.backlogHolds);
        capacitySupport.refreshMetric();
        Summary summary = tally.summary();
        log.info("쿠폰 발급 대사를 마쳤다 {}", summary);
        return summary;
    }

    /** 쿠폰 하나를 대사한다 */
    Outcome reconcile(long couponId) {
        Read read = read(couponId);
        if (read == null) {
            return Outcome.skip();
        }
        if (read.snapshot().hold() == IssueHoldReason.RECOVERY
                || (read.snapshot().controlExists() && !read.snapshot().ready())) {
            log.info("복구 중인 쿠폰이라 대사를 건너뛴다 couponId={}, hold={}", couponId, read.snapshot().hold());
            return Outcome.skip();
        }
        if (!read.snapshot().controlExists()) {
            return reconcileWithoutControl(couponId, read);
        }
        Judgment judgment = judge(read);
        if (!judgment.matched()) {
            // 동시 쓰기 사이의 한 번 읽기로 확정하지 않는다. 같은 순서로 다시 읽어 그래도 맞지 않을 때만 불일치다
            Read again = read(couponId);
            if (again == null || !again.snapshot().controlExists() || !again.snapshot().ready()
                    || again.snapshot().hold() == IssueHoldReason.RECOVERY) {
                return Outcome.skip();
            }
            read = again;
            judgment = judge(again);
            if (!judgment.matched()) {
                retainKeys(couponId, judgment);
                return confirmMismatch(couponId, judgment);
            }
        }
        retainKeys(couponId, judgment);
        return applyMatched(couponId, read, judgment);
    }

    /**
     * DB 결과가 없는 기록이 남았으면 key 만료를 지금 + 여유까지 늦춘다(줄이지 않는다). 실패해도 대사의 나머지(보류·정리)를 막지 않게 WARN만
     * 남기고 다음 회차가 다시 늦춘다.
     */
    private void retainKeys(long couponId, Judgment judgment) {
        if (!judgment.retainKeys()) {
            return;
        }
        Instant expireAt = redisProperties.extendedKeysExpireAt(clock.instant());
        try {
            int extended = issueStore.extendKeysExpiry(couponId, expireAt);
            if (extended > 0) {
                log.debug("DB 결과가 없는 접수 기록이 남아 쿠폰 key 만료를 늦췄다 couponId={}, expireAt={}, extendedKeys={}, {}",
                        couponId, expireAt, extended, judgment.describe());
            }
        } catch (RuntimeException exception) {
            log.warn("DB 결과가 없는 접수 기록이 남은 쿠폰의 key 만료를 늦추지 못했다. 다음 회차에 다시 늦춘다 couponId={}, expireAt={}",
                    couponId, expireAt, exception);
        }
    }

    /** DB를 먼저 읽고 Redis를 읽는다. 쿠폰이 없으면 null */
    private Read read(long couponId) {
        Read db = snapshotTransaction.execute(status -> {
            Coupon coupon = couponRepository.findById(couponId).orElse(null);
            if (coupon == null) {
                return null;
            }
            Map<String, DbResult> results = new HashMap<>();
            for (IssueResultRow row : resultRepository.findRowsByCouponId(couponId)) {
                results.put(row.issueRequestId(), new DbResult(row.memberId(), row.result(), row.epoch()));
            }
            return new Read(coupon.getStatus(), coupon.getQuantity(), coupon.getIssueEndAt(), results, null);
        });
        if (db == null) {
            log.warn("용량 예약은 있는데 쿠폰이 없어 대사를 건너뛴다 couponId={}", couponId);
            return null;
        }
        return new Read(db.status(), db.quantity(), db.issueEndAt(), db.dbResults(), store.snapshot(couponId));
    }

    private Judgment judge(Read read) {
        Snapshot snapshot = read.snapshot();
        Instant now = clock.instant();
        // 발급 종료 뒤 여유는 재발행 대기 시간을 쓴다(종료 직전에 시각을 읽고 늦게 Lua에 닿은 접수와 겨루지 않는다. 길어도 정리만 늦어진다)
        boolean stillAccepting = IssueReconciliationRule.stillAccepting(read.status(), snapshot.status(),
                read.issueEndAt(), now, delivery.republishAfter());
        return IssueReconciliationRule.judge(new IssueReconciliationRule.Input(read.quantity(), snapshot.epoch(),
                snapshot.stock(), snapshot.soldOut(), snapshot.users(), snapshot.requests(), read.dbResults(),
                now, delivery.republishAfter(), stillAccepting));
    }

    /**
     * issue-control이 없다.
     * <ul>
     *     <li>고아 예약({@link IssueCapacityRule#orphan}: 발급 중이 아니고 key가 하나도 없다): 예약을 푼다. 열기 전 쿠폰(DRAFT·REVIEW)은 예약 뒤
     *     대기 시간이 지난 것만 푼다(진행 중인 열기일 수 있다).</li>
     *     <li>다시 열 수 없는 쿠폰이고 남은 요청 기록이 없으면 남은 key를 지우고 예약을 푼다(앞 회차가 key 일부만 남기고 멈춘 경우).</li>
     *     <li>발급 중이던 쿠폰이면 유실 의심으로 알리기만 한다. 열기 전 쿠폰에 key가 남았으면(열기 중이거나 준비 뒤 실패) 아무것도 하지 않는다.</li>
     * </ul>
     */
    private Outcome reconcileWithoutControl(long couponId, Read read) {
        CouponStatus status = read.status();
        Snapshot snapshot = read.snapshot();
        if (IssueCapacityRule.orphan(status, snapshot.redisKeysExist())) {
            Instant cutoff = IssueCapacityRule.orphanCutoff(status, clock.instant(), limits.orphanReservationGrace());
            boolean released = cutoff == null ? capacitySupport.release(couponId)
                    : capacitySupport.releaseOrphan(couponId, cutoff);
            if (released) {
                log.info("Redis key가 없는 쿠폰의 용량 예약을 고아로 보고 풀었다 couponId={}, status={}", couponId, status);
            }
            return released ? Outcome.matchedReleased(0) : Outcome.matched(0);
        }
        if (IssueCapacityRule.releasable(status, snapshot.requests().size(), 0, 0)) {
            return store.cleanUp(couponId) && releaseCapacity(couponId) ? Outcome.matchedReleased(0) : Outcome.matched(0);
        }
        if (status == CouponStatus.OPEN || status == CouponStatus.SUSPENDED || status == CouponStatus.CLOSED) {
            log.warn("발급을 연 쿠폰의 Redis 준비 기록(issue-control)이 없어 대사하지 못했다. 유실이면 복구 절차가 필요하다 couponId={}, status={}, "
                    + "requests={}", couponId, status, snapshot.requests().size());
        }
        return Outcome.skip();
    }

    private Outcome confirmMismatch(long couponId, Judgment judgment) {
        boolean held = store.hold(couponId, IssueHoldReason.RECONCILE);
        if (held) {
            log.error("{} 쿠폰 발급 수량 대사가 맞지 않아 신규 접수를 보류했다 couponId={}, kind={}, {}", MISMATCH_LOG_TAG,
                    couponId, judgment.mismatch(), judgment.describe());
        } else {
            log.warn("쿠폰 발급 수량 대사가 여전히 맞지 않는다(이미 보류 중) couponId={}, kind={}, {}", couponId,
                    judgment.mismatch(), judgment.describe());
        }
        return Outcome.mismatched(judgment.mismatch());
    }

    private Outcome applyMatched(long couponId, Read read, Judgment judgment) {
        Snapshot snapshot = read.snapshot();
        if (snapshot.hold() == IssueHoldReason.RECONCILE) {
            log.warn("대사 불일치 보류가 남은 쿠폰이라 기록 정리·고치기를 하지 않는다. 원인을 확인한 뒤 보류를 푼다 couponId={}, {}", couponId,
                    judgment.describe());
            return Outcome.matched(0);
        }
        if (judgment.staleUnresolved() > 0) {
            log.warn("DB 결과가 없는 이전 epoch 접수 기록이 남아 있다(지우지 않음). 복구 미처리 확인을 다시 본다 couponId={}, epoch={}, "
                    + "staleUnresolved={}", couponId, snapshot.epoch(), judgment.staleUnresolved());
        }
        long removed = judgment.finishedRequestIds().isEmpty() ? 0
                : store.removeRequests(couponId, judgment.finishedRequestIds());
        repair(couponId, judgment);
        IssueHoldReason holdAfter = applyBacklog(couponId, snapshot.hold(), judgment.waitingRepublish());

        boolean released = false;
        if (IssueCapacityRule.releasable(read.status(), judgment.requestsLeft(), judgment.inProgress(),
                judgment.pendingCompensation())) {
            released = store.cleanUp(couponId) && releaseCapacity(couponId);
        }
        Outcome outcome = released ? Outcome.matchedReleased(removed) : Outcome.matched(removed);
        return holdAfter == IssueHoldReason.BACKLOG ? outcome.withBacklogHold() : outcome;
    }

    private void repair(long couponId, Judgment judgment) {
        if (judgment.extraUsers() > 0) {
            // 그사이 새 접수와 겨룰 수 있어 지우지 않는다. 남은 회원은 같은 쿠폰을 다시 받지 못할 뿐 총량·중복 제약은 지켜진다
            log.warn("확정 발급·진행 중 접수에 없는 회원 기록이 users에 있다(고치지 않음) couponId={}, extraUsers={}", couponId,
                    judgment.extraUsers());
        }
        if (judgment.soldOutRepair() == SoldOutRepair.NONE && judgment.missingUsers().isEmpty()) {
            return;
        }
        RepairResult result = store.repair(couponId, judgment.soldOutRepair(), judgment.missingUsers());
        if (result.soldOutChange() != 0 || result.addedUsers() > 0) {
            log.warn("쿠폰 매진 표시·회원 기록을 대사 결과에 맞게 고쳤다 couponId={}, soldOutChange={}, addedUsers={}", couponId,
                    result.soldOutChange(), result.addedUsers());
        }
    }

    /** 적체 보류를 걸거나 푼다. 결과 보류 사유를 돌려준다 */
    private IssueHoldReason applyBacklog(long couponId, IssueHoldReason current, long waiting) {
        switch (IssueBacklogRule.decide(waiting, limits.backlogThreshold(), limits.backlogReleaseAt(), current)) {
            case HOLD -> {
                if (store.hold(couponId, IssueHoldReason.BACKLOG)) {
                    log.warn("재발행 대기가 임계치를 넘어 신규 접수를 보류했다 couponId={}, waiting={}, threshold={}", couponId, waiting,
                            limits.backlogThreshold());
                }
                return IssueHoldReason.BACKLOG;
            }
            case RELEASE -> {
                if (store.releaseHold(couponId, IssueHoldReason.BACKLOG)) {
                    log.info("재발행 대기가 해제 기준 이하로 줄어 적체 보류를 풀었다 couponId={}, waiting={}, releaseAt={}", couponId,
                            waiting, limits.backlogReleaseAt());
                }
                return null;
            }
            default -> {
                return current;
            }
        }
    }

    private boolean releaseCapacity(long couponId) {
        boolean released = capacitySupport.release(couponId);
        if (released) {
            log.info("쿠폰 Redis 기록을 모두 정리해 용량 예약을 풀었다 couponId={}", couponId);
        }
        return released;
    }

    private record Read(CouponStatus status, int quantity, Instant issueEndAt, Map<String, DbResult> dbResults,
                        Snapshot snapshot) {
    }

    /** 쿠폰 하나의 대사 결과 */
    record Outcome(Mismatch mismatch, boolean skipped, long removedRequests, boolean released, boolean backlogHold) {

        static Outcome skip() {
            return new Outcome(null, true, 0, false, false);
        }

        static Outcome matched(long removed) {
            return new Outcome(null, false, removed, false, false);
        }

        static Outcome matchedReleased(long removed) {
            return new Outcome(null, false, removed, true, false);
        }

        static Outcome mismatched(Mismatch mismatch) {
            return new Outcome(mismatch, false, 0, false, false);
        }

        Outcome withBacklogHold() {
            return new Outcome(mismatch, skipped, removedRequests, released, true);
        }
    }

    /**
     * 대사 회차 결과.
     *
     * @param coupons         대상 쿠폰 수(용량 예약이 있는 쿠폰)
     * @param mismatched      불일치로 확정한 쿠폰 수
     * @param skipped         건너뛴 쿠폰 수(복구 중·준비 전·issue-control 없음)
     * @param failed          예외로 끝내지 못한 쿠폰 수
     * @param removedRequests 지운 요청 기록 수
     * @param released        용량 예약을 푼 쿠폰 수
     * @param backlogHolds    회차 뒤 적체 보류 중인 쿠폰 수
     */
    public record Summary(int coupons, int mismatched, int skipped, int failed, long removedRequests, int released,
                          long backlogHolds) {
    }

    private static final class Tally {
        private final Map<Mismatch, Long> mismatches = new EnumMap<>(Mismatch.class);
        private int coupons;
        private int skipped;
        private int failed;
        private long removed;
        private int released;
        private long backlogHolds;

        void add(Outcome outcome) {
            coupons++;
            if (outcome.mismatch() != null) {
                mismatches.merge(outcome.mismatch(), 1L, Long::sum);
            }
            if (outcome.skipped()) {
                skipped++;
            }
            removed += outcome.removedRequests();
            if (outcome.released()) {
                released++;
            }
            if (outcome.backlogHold()) {
                backlogHolds++;
            }
        }

        Summary summary() {
            int mismatched = mismatches.values().stream().mapToInt(Long::intValue).sum();
            return new Summary(coupons + failed, mismatched, skipped, failed, removed, released, backlogHolds);
        }
    }
}
