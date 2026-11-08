package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.port.CouponIssueEventPublisher;
import com.hapbang.coupon.app.port.CouponIssueEventPublisher.PublishResult;
import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueControlState;
import com.hapbang.coupon.app.port.CouponIssueStore.RequestEntry;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.config.CouponIssueDeliveryProperties;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.IssueRequestRecord;
import com.hapbang.coupon.domain.RepublishRule;
import com.hapbang.coupon.domain.RepublishRule.Decision;
import com.hapbang.coupon.out.CouponDltLogRepository;
import com.hapbang.coupon.out.CouponRepublishQueryRepository;
import com.hapbang.coupon.out.RepublishTargetCoupon;
import com.hapbang.coupon.out.redis.CouponRedisProperties;
import com.hapbang.shared.coupon.event.CouponIssueRequested;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 발급 요청 재발행(배치). Redis requests에 접수 기록이 있는데 DB {@code coupon_issue_request}에 결과가 없는 요청을 같은 요청 ID·같은 값으로
 * Kafka에 다시 보낸다. 접수 직후 전송이 실패했거나, ACK 뒤 소비가 실패·유실돼도 결국 DB 결과가 생기게 한다(I3). 판정은 {@link RepublishRule}.
 * <ul>
 *     <li>대상 쿠폰: Redis 준비를 마친 OPEN·SUSPENDED와, 접수 기록이 남은(정리 전) CLOSED.</li>
 *     <li>epoch: 접수 기록의 접수 epoch를 싣는다. 접수 epoch가 없는 옛 형식 기록이면 {@code issue-control}의 지금 epoch를 싣는다(복구가
 *     아니면 epoch는 바뀌지 않으므로 접수 당시 epoch와 같다). 그래서 {@code issue-control}이 준비(ready=1)이고 그 epoch가 DB 쿠폰의 epoch와
 *     같을 때만 보낸다. 복구 중(ready=0)이거나 둘이 다르면 그 쿠폰은 건너뛴다(복구 절차가 직접 재발행한다).</li>
 *     <li>전송 직전 확인: 쿠폰을 훑기 전에 한 번만 보지 않고 <b>매 전송 직전에</b> {@code issue-control}을 다시 읽어(HGET 한 번) 준비(ready=1)와
 *     epoch가 그대로인지 확인한다. 아니면 그 쿠폰의 남은 전송을 멈춘다. 그래서 복구가 접수를 차단(ready=0)한 뒤에 새로 시작하는 재발행
 *     전송은 없고, 차단 직전에 확인을 통과해 시작한 전송은 많아야 하나다. 그 하나는 복구가 Kafka 경계를 잡기 전에 기다리는 전송 수명
 *     ({@link CouponIssueDeliveryProperties#maxSendLifetime}: 전송 준비 대기 + max(전송 수명, 재발행 ACK 대기))이 덮는다.</li>
 *     <li>DB 확인은 HSCAN 한 묶음마다 한 번이다(이 배치는 접수 경로가 아니라 DB를 읽어도 된다). 아직 처리되지 않은 DLT 요청(PENDING·
 *     재발행 선점 중인 REPLAYING)은 쿠폰마다 한 번 읽어 거른다.</li>
 *     <li>접수 epoch가 지금 epoch보다 작은 기록은 보내지 않는다(소비자가 epoch 불일치로 격리한다). 개수만 지표와 DEBUG 로그로 남긴다
 *     (대사가 이전 epoch 미확정으로 알린다).</li>
 *     <li>접수된 요청을 지우지 않는다. 기록 정리는 DB 결과를 확인한 대사가 한다.</li>
 *     <li>인스턴스 여럿이 같은 요청을 함께 보내도 소비자 멱등(요청 ID 유일 제약)이 흡수하므로 선점(분산 락)은 두지 않는다
 *     (강의 이벤트 재발행과 같은 판단).</li>
 *     <li>연속 실패: 한 쿠폰에서 이번 회차에 전송이 연속으로 {@code hapbang.coupon.republish.max-consecutive-failures}건(기본 3) 실패
 *     (FAILED·TIMED_OUT)하면 그 쿠폰의 남은 전송을 멈추고 다음 쿠폰으로 간다. broker 장애 때 후보마다 ACK 대기로 회차가 끝나지 않는 것을
 *     막는다. ACK가 한 번 끼면 다시 센다. 멈춘 요청은 기록이 그대로라 다음 회차가 다시 본다.</li>
 *     <li>key 만료 연장: DB 결과가 없는 요청(다시 보낼 요청·DLT에서 기다리는 요청)을 본 쿠폰은 훑기를 마친 뒤(멈췄어도) key 만료를
 *     지금 + {@code hapbang.coupon.redis.keys-expire-margin}까지 늦춘다({@link CouponIssueStore#extendKeysExpiry}, 줄이지 않는다). 발급 종료 +
 *     여유에 key가 만료돼 미확정 요청의 근거가 사라지지 않게 한다. 이전 epoch 미확정 기록은 DB를 보지 않으므로 대사가 늦춘다.</li>
 * </ul>
 * 한 쿠폰의 실패(Redis·DB 오류)는 그 쿠폰만 건너뛰고 다음 쿠폰을 계속한다. 회차 결과는 쿠폰별 보냄·ACK·실패·멈춤을 담은
 * {@link RepublishSummary}이고 WARN 로그로 남긴다.
 */
@Slf4j
@Service
public class CouponRepublishUseCase {

    /** 재발행 건수. {@code result} 태그: acked·failed·timed_out */
    static final String REPUBLISHED_METRIC = "coupon.issue.republished";
    /** 지난 회차에 재발행 대상이던 요청 중 가장 오래된 것의 접수 뒤 경과(초). 대상이 없으면 0 */
    static final String OLDEST_AGE_METRIC = "coupon.issue.republish.oldest.age";
    /**
     * 이전 epoch에 접수돼 재발행 대상에서 뺀 접수 기록 수(회차마다 더한다). DB 결과를 확인하기 전 수라 이미 결과가 있는 기록도 들어간다
     * (미확정 수는 대사가 센다)
     */
    static final String STALE_EPOCH_METRIC = "coupon.issue.republish.stale.epoch";
    /** 마지막으로 재발행을 ACK받은 시각(epoch 초). 없으면 0 */
    static final String LAST_REPUBLISHED_METRIC = "coupon.issue.republish.last";

    private static final List<CouponStatus> TARGET_STATUSES =
            List.of(CouponStatus.OPEN, CouponStatus.SUSPENDED, CouponStatus.CLOSED);

    private final CouponRepublishQueryRepository republishQueryRepository;
    private final CouponDltLogRepository couponDltLogRepository;
    private final CouponIssueStore couponIssueStore;
    private final CouponIssueEventPublisher couponIssueEventPublisher;
    private final CouponIssueDeliveryProperties properties;
    private final CouponRedisProperties redisProperties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private final AtomicLong lastRepublishedEpochSecond = new AtomicLong();

    public CouponRepublishUseCase(CouponRepublishQueryRepository republishQueryRepository,
                                  CouponDltLogRepository couponDltLogRepository,
                                  CouponIssueStore couponIssueStore,
                                  CouponIssueEventPublisher couponIssueEventPublisher,
                                  CouponIssueDeliveryProperties properties,
                                  CouponRedisProperties redisProperties,
                                  ObjectProvider<MeterRegistry> meterRegistry,
                                  @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.republishQueryRepository = republishQueryRepository;
        this.couponDltLogRepository = couponDltLogRepository;
        this.couponIssueStore = couponIssueStore;
        this.couponIssueEventPublisher = couponIssueEventPublisher;
        this.properties = properties;
        this.redisProperties = redisProperties;
        this.meterRegistry = meterRegistry.getIfAvailable(SimpleMeterRegistry::new);
        this.clock = clock;
        Gauge.builder(OLDEST_AGE_METRIC, oldestAgeSeconds, AtomicLong::get).baseUnit("seconds")
                .description("재발행 대상 중 가장 오래된 요청의 접수 뒤 경과").register(this.meterRegistry);
        Gauge.builder(LAST_REPUBLISHED_METRIC, lastRepublishedEpochSecond, AtomicLong::get).baseUnit("seconds")
                .description("마지막 재발행 ACK 시각(epoch 초)").register(this.meterRegistry);
    }

    /** 한 회차를 실행한다 */
    public RepublishSummary republishDue() {
        Instant now = clock.instant();
        Run run = new Run(now);
        List<CouponResult> results = new ArrayList<>();
        for (RepublishTargetCoupon target : republishQueryRepository.findTargets(TARGET_STATUSES)) {
            CouponRun coupon = new CouponRun(target.couponId());
            try {
                republishCoupon(target, run, coupon);
            } catch (RuntimeException e) {
                coupon.ending = Ending.ERROR;
                log.warn("발급 요청 재발행 중 오류가 나 이 쿠폰은 다음 실행에서 다시 본다 couponId={}", target.couponId(), e);
            }
            if (coupon.sent() > 0 || coupon.ending != Ending.COMPLETED) {
                results.add(coupon.result());
            }
        }
        oldestAgeSeconds.set(run.oldestAcceptedAt == null ? 0
                : Math.max(0, Duration.between(run.oldestAcceptedAt, now).toSeconds()));
        RepublishSummary summary = new RepublishSummary(results);
        if (summary.acked() > 0) {
            lastRepublishedEpochSecond.set(clock.instant().getEpochSecond());
        }
        if (summary.republished() > 0 || summary.failedCoupons() > 0) {
            log.warn("DB에 결과가 없는 발급 요청 재발행 회차를 마쳤다 republished={}, acked={}, failed={}, stoppedCoupons={}, "
                    + "failedCoupons={}, stopped={}", summary.republished(), summary.acked(), summary.failed(),
                    summary.stoppedCoupons(), summary.failedCoupons(), summary.stopped());
        }
        return summary;
    }

    private void republishCoupon(RepublishTargetCoupon target, Run run, CouponRun coupon) {
        long couponId = target.couponId();
        if (target.status() == CouponStatus.CLOSED && !couponIssueStore.hasRequests(couponId)) {
            return;
        }
        Optional<IssueControlState> control = couponIssueStore.findIssueControl(couponId);
        if (control.isEmpty()) {
            if (couponIssueStore.hasRequests(couponId)) {
                log.warn("issue-control 없이 접수 기록이 남아 재발행하지 않는다 couponId={}", couponId);
            }
            return;
        }
        IssueControlState state = control.get();
        if (!state.ready() || state.epoch() == null || state.epoch() != target.epoch()) {
            log.info("복구 중이거나 epoch가 DB와 달라 재발행하지 않는다 couponId={}, ready={}, redisEpoch={}, dbEpoch={}",
                    couponId, state.ready(), state.epoch(), target.epoch());
            return;
        }
        Set<String> dltUnresolved = new HashSet<>(couponDltLogRepository.findUnresolvedRequestIds(couponId));
        try {
            couponIssueStore.scanRequests(couponId, properties.republish().scanCount(),
                    page -> republishPage(couponId, state.epoch(), page, dltUnresolved, run, coupon));
        } catch (IssueControlChanged stopped) {
            coupon.ending = Ending.ISSUE_CONTROL_CHANGED;
            log.info("재발행 중 접수가 차단됐거나 epoch가 바뀌어 이 쿠폰의 남은 재발행을 멈췄다 couponId={}, epoch={}, ready={}, "
                    + "redisEpoch={}", couponId, state.epoch(), stopped.ready, stopped.redisEpoch);
        } catch (ConsecutiveFailures stopped) {
            coupon.ending = Ending.CONSECUTIVE_FAILURES;
            log.warn("재발행이 연속으로 실패해 이 쿠폰의 남은 재발행을 이번 회차에서 멈췄다. 다음 회차가 다시 본다 couponId={}, epoch={}, "
                    + "consecutiveFailures={}, sent={}, failed={}", couponId, state.epoch(),
                    coupon.consecutiveFailures, coupon.sent(), coupon.failed);
        } finally {
            if (coupon.unresolvedSeen) {
                extendKeysExpiry(couponId, run.now);
            }
        }
    }

    /**
     * DB 결과가 없는 요청을 본 쿠폰의 key 만료를 지금 + 여유까지 늦춘다(줄이지 않는다). 실패해도 재발행 결과에 영향이 없게 WARN만 남기고
     * 다음 회차가 다시 늦춘다.
     */
    private void extendKeysExpiry(long couponId, Instant now) {
        Instant expireAt = redisProperties.extendedKeysExpireAt(now);
        try {
            int extended = couponIssueStore.extendKeysExpiry(couponId, expireAt);
            if (extended > 0) {
                log.debug("DB 결과가 없는 요청이 남아 쿠폰 key 만료를 늦췄다 couponId={}, expireAt={}, extendedKeys={}", couponId,
                        expireAt, extended);
            }
        } catch (RuntimeException e) {
            log.warn("DB 결과가 없는 요청이 남은 쿠폰의 key 만료를 늦추지 못했다. 다음 회차에 다시 늦춘다 couponId={}, expireAt={}",
                    couponId, expireAt, e);
        }
    }

    /**
     * 전송 직전 확인: {@code issue-control}이 아직 준비(ready=1)이고 epoch가 {@code epoch}와 같은가. 아니면 {@link IssueControlChanged}로 그
     * 쿠폰의 훑기를 멈춘다. Redis 오류는 그대로 나가 그 쿠폰을 실패로 건너뛴다(보내지 않는 쪽).
     */
    private void requireStillReady(long couponId, long epoch) {
        Optional<IssueControlState> control = couponIssueStore.findIssueControl(couponId);
        if (control.isEmpty()) {
            throw new IssueControlChanged(false, null);
        }
        IssueControlState state = control.get();
        if (!state.ready() || state.epoch() == null || state.epoch() != epoch) {
            throw new IssueControlChanged(state.ready(), state.epoch());
        }
    }

    private void republishPage(long couponId, long epoch, List<RequestEntry> page, Set<String> dltUnresolved,
                               Run run, CouponRun coupon) {
        Duration republishAfter = properties.republishAfter();
        List<Candidate> candidates = new ArrayList<>();
        int malformed = 0;
        int staleEpoch = 0;
        for (RequestEntry entry : page) {
            IssueRequestRecord record;
            try {
                record = IssueRequestRecord.parse(entry.value());
            } catch (IllegalArgumentException e) {
                malformed++;
                continue;
            }
            if (!record.compensated() && RepublishRule.staleEpoch(record, epoch)) {
                staleEpoch++;
            } else if (RepublishRule.needsCheck(record, run.now, republishAfter, epoch)) {
                candidates.add(new Candidate(entry.issueRequestId(), record));
            }
        }
        if (malformed > 0) {
            log.warn("형식을 읽지 못한 접수 기록을 재발행하지 않았다 couponId={}, malformed={}", couponId, malformed);
        }
        if (staleEpoch > 0) {
            meterRegistry.counter(STALE_EPOCH_METRIC).increment(staleEpoch);
            log.debug("이전 epoch에 접수된 기록을 재발행 대상에서 뺐다(DB 확인 전 수) couponId={}, epoch={}, staleEpoch={}",
                    couponId, epoch, staleEpoch);
        }
        if (candidates.isEmpty()) {
            return;
        }
        List<String> candidateIds = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            candidateIds.add(candidate.issueRequestId());
        }
        Set<String> recorded = new HashSet<>(republishQueryRepository.findRecordedIds(couponId, candidateIds));
        for (Candidate candidate : candidates) {
            Decision decision = RepublishRule.decide(candidate.record(), run.now, republishAfter, epoch,
                    recorded.contains(candidate.issueRequestId()), dltUnresolved.contains(candidate.issueRequestId()));
            if (decision.unresolved()) {
                coupon.unresolvedSeen = true;
            }
            if (decision == Decision.REPUBLISH) {
                publish(couponId, epoch, candidate, run, coupon);
            }
        }
    }

    private void publish(long couponId, long epoch, Candidate candidate, Run run, CouponRun coupon) {
        requireStillReady(couponId, epoch);
        IssueRequestRecord record = candidate.record();
        run.noteAccepted(record.acceptedAt());
        // 접수 기록의 접수 epoch를 싣는다. 옛 형식 기록(접수 epoch 없음)이면 지금 issue-control의 epoch다
        CouponIssueRequested event = new CouponIssueRequested(candidate.issueRequestId(), couponId, record.memberId(),
                record.epochOr(epoch), record.acceptedAt(), record.expiresAt());
        PublishResult result;
        try {
            result = couponIssueEventPublisher.publish(event, properties.republish().ackTimeout());
        } catch (RuntimeException e) {
            result = PublishResult.FAILED;
        }
        meterRegistry.counter(REPUBLISHED_METRIC, "result", result.name().toLowerCase(Locale.ROOT)).increment();
        if (result.acked()) {
            coupon.acked++;
            coupon.consecutiveFailures = 0;
            return;
        }
        coupon.failed++;
        coupon.consecutiveFailures++;
        if (RepublishRule.stopAfterFailures(coupon.consecutiveFailures,
                properties.republish().maxConsecutiveFailures())) {
            throw new ConsecutiveFailures();
        }
    }

    /**
     * 한 회차 결과. 다시 보냈거나 끝까지 훑지 못한 쿠폰만 담는다.
     *
     * @param coupons 쿠폰별 결과
     */
    public record RepublishSummary(List<CouponResult> coupons) {

        public RepublishSummary {
            coupons = List.copyOf(coupons);
        }

        /** 다시 보낸 요청 수(ACK 여부와 관계없이) */
        public int republished() {
            return coupons.stream().mapToInt(CouponResult::sent).sum();
        }

        /** 그중 ACK받은 수 */
        public int acked() {
            return coupons.stream().mapToInt(CouponResult::acked).sum();
        }

        /** 그중 실패(FAILED·TIMED_OUT)한 수 */
        public int failed() {
            return coupons.stream().mapToInt(CouponResult::failed).sum();
        }

        /** 오류로 건너뛴 쿠폰 수 */
        public int failedCoupons() {
            return (int) coupons.stream().filter(coupon -> coupon.ending() == Ending.ERROR).count();
        }

        /** 연속 실패로 남은 전송을 멈춘 쿠폰 수 */
        public int stoppedCoupons() {
            return stopped().size();
        }

        /** 연속 실패로 남은 전송을 멈춘 쿠폰 */
        public List<CouponResult> stopped() {
            return coupons.stream().filter(coupon -> coupon.ending() == Ending.CONSECUTIVE_FAILURES).toList();
        }
    }

    /**
     * 한 쿠폰의 회차 결과.
     *
     * @param sent   다시 보낸 요청 수(= acked + failed)
     * @param acked  ACK받은 수
     * @param failed 실패(FAILED·TIMED_OUT)한 수
     * @param ending 훑기를 어떻게 끝냈는가
     */
    public record CouponResult(long couponId, int sent, int acked, int failed, Ending ending) {

        /** 끝까지 훑지 못했는가(멈춤·오류) */
        public boolean stopped() {
            return ending != Ending.COMPLETED;
        }
    }

    /** 한 쿠폰의 훑기를 끝낸 방식 */
    public enum Ending {
        /** 끝까지 훑었다(건너뛴 쿠폰 포함) */
        COMPLETED,
        /** 연속 실패가 상한에 닿아 남은 전송을 멈췄다 */
        CONSECUTIVE_FAILURES,
        /** 전송 직전 확인에서 접수 차단·epoch 변경을 봐 멈췄다 */
        ISSUE_CONTROL_CHANGED,
        /** Redis·DB 오류로 건너뛰었다 */
        ERROR
    }

    private record Candidate(String issueRequestId, IssueRequestRecord record) {
    }

    /** 전송 직전 확인에서 접수 차단·epoch 변경을 봤다. 그 쿠폰의 훑기를 멈추려고 던진다 */
    private static final class IssueControlChanged extends RuntimeException {

        private final boolean ready;
        private final Long redisEpoch;

        private IssueControlChanged(boolean ready, Long redisEpoch) {
            super(null, null, false, false);
            this.ready = ready;
            this.redisEpoch = redisEpoch;
        }
    }

    /** 연속 실패가 상한에 닿았다. 그 쿠폰의 훑기를 멈추려고 던진다 */
    private static final class ConsecutiveFailures extends RuntimeException {

        private ConsecutiveFailures() {
            super(null, null, false, false);
        }
    }

    /** 한 쿠폰의 회차 안 집계 */
    private static final class CouponRun {

        private final long couponId;
        private int acked;
        private int failed;
        private int consecutiveFailures;
        private boolean unresolvedSeen;
        private Ending ending = Ending.COMPLETED;

        private CouponRun(long couponId) {
            this.couponId = couponId;
        }

        private int sent() {
            return acked + failed;
        }

        private CouponResult result() {
            return new CouponResult(couponId, sent(), acked, failed, ending);
        }
    }

    /** 회차 안의 집계 */
    private static final class Run {

        private final Instant now;
        private Instant oldestAcceptedAt;

        private Run(Instant now) {
            this.now = now;
        }

        private void noteAccepted(Instant acceptedAt) {
            if (oldestAcceptedAt == null || acceptedAt.isBefore(oldestAcceptedAt)) {
                oldestAcceptedAt = acceptedAt;
            }
        }
    }
}
