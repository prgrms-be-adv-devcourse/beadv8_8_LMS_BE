package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.IssueRequestResult;
import com.hapbang.coupon.app.port.CouponIssueEventPublisher;
import com.hapbang.coupon.app.port.CouponIssueEventPublisher.PublishResult;
import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueAttempt;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueOutcome;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.domain.IssueResponseCode;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.shared.coupon.event.CouponIssueRequested;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 선착순 발급 접수. 접수 판정(운영 상태·준비·기간·회원 중복·재고)과 epoch·만료 시각은 Redis Lua가 정하고, DB를 읽지 않는다
 * (발급 경로는 Redis만 쓴다. DB 커넥션 풀을 다른 기능과 함께 쓰므로 접수 폭주가 DB로 번지지 않게 한다).
 * 쿠폰이 없거나 준비 전이면 Lua가 준비 안 됨(-2)이라 409 {@code COUPON_ISSUE_NOT_OPEN}이다.
 * 접수(ACCEPTED)는 완료가 아니다. 결과는 발급 요청 상태 조회로 확인한다.
 * <p>
 * 같은 요청 ID를 다시 보내면 Lua가 기존 접수 기록을 바꾸지 않고 2(ALREADY_ACCEPTED)와 최초 접수의 만료 시각을 돌려준다.
 * 발급이 중단된 뒤의 재전송은 Lua 검사 순서상 NOT_OPEN이다.
 * <p>
 * 매진: 매진 표시 메모리 캐시({@link CouponSoldOutCache})가 매진이라고 하면 Lua를 부르지 않고 409 {@code COUPON_SOLD_OUT}이다.
 * 그래서 매진 뒤에는 같은 요청 재전송도 ALREADY_ACCEPTED가 아니라 매진으로 응답한다(결과는 상태 조회로 확인한다).
 * <p>
 * 접수 직후 전송: Lua가 새로 접수(1)하면 {@link CouponIssueRequested}를 Kafka로 보내고 ACK를 기다리지 않고 응답한다
 * (요청 스레드를 붙잡는 것은 전송 준비 {@code hapbang.coupon.publish-ack-timeout}뿐이다). 전송 결과는 완료 콜백이 지표·로그로만 남기고
 * Redis에 쓰지 않는다. 전송이 실패하거나 시간이 지나도 응답은 ACCEPTED이고 재고를 되돌리지 않는다: 접수 기록이 남아 있으므로 재발행 배치가
 * 다시 보낸다.
 * 재응답(2)은 보내지 않는다(최초 접수나 재발행이 보낸다).
 */
@Slf4j
@Service
public class CouponIssueRequestUseCase {

    /** 접수 직후 전송에 실패한 수. {@code result} 태그: failed·timed_out */
    static final String PUBLISH_FAILURE_METRIC = "coupon.issue.publish.failures";

    /** 매진(409 {@code COUPON_SOLD_OUT})으로 거절한 접수 수. 매진 표시 캐시로 막은 것과 Lua가 매진을 돌려준 것을 함께 센다 */
    static final String SOLD_OUT_METRIC = "coupon.issue.sold_out";

    private final CouponIssueStore couponIssueStore;
    private final CouponIssueEventPublisher couponIssueEventPublisher;
    private final CouponSoldOutCache couponSoldOutCache;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    /** 전송 실패 로그를 상태가 바뀔 때만 남긴다(broker 장애 동안 접수마다 WARN이 쌓이지 않게) */
    private final AtomicBoolean publishFailing = new AtomicBoolean();

    public CouponIssueRequestUseCase(CouponIssueStore couponIssueStore,
                                     CouponIssueEventPublisher couponIssueEventPublisher,
                                     CouponSoldOutCache couponSoldOutCache,
                                     ObjectProvider<MeterRegistry> meterRegistry,
                                     @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponIssueStore = couponIssueStore;
        this.couponIssueEventPublisher = couponIssueEventPublisher;
        this.couponSoldOutCache = couponSoldOutCache;
        this.meterRegistry = meterRegistry.getIfAvailable(SimpleMeterRegistry::new);
        this.clock = clock;
    }

    public IssueRequestResult requestIssue(long couponId, long memberId, String issueRequestId) {
        IssueRequestIds.requireValid(issueRequestId);
        if (couponSoldOutCache.isSoldOut(couponId)) {
            meterRegistry.counter(SOLD_OUT_METRIC).increment();
            throw new CouponException(CouponErrorCode.COUPON_SOLD_OUT);
        }
        // Redis 접수 기록은 밀리초까지만 남으므로 접수 시각도 밀리초로 자른다
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        IssueOutcome outcome = couponIssueStore.tryIssue(new IssueAttempt(couponId, memberId, issueRequestId, issuedAt));
        IssueResponseCode code = IssueResponseCode.fromLua(outcome.code());
        if (code == IssueResponseCode.SOLD_OUT) {
            couponSoldOutCache.markSoldOut(couponId);
            meterRegistry.counter(SOLD_OUT_METRIC).increment();
        }
        if (!code.isAccepted()) {
            throw new CouponException(errorCodeOf(code));
        }
        if (code == IssueResponseCode.ACCEPTED) {
            log.info("쿠폰 발급을 접수했다 couponId={}, memberId={}, issueRequestId={}", couponId, memberId, issueRequestId);
            publishAccepted(new CouponIssueRequested(issueRequestId, couponId, memberId, outcome.epoch(), issuedAt,
                    outcome.expiresAt()));
        }
        return new IssueRequestResult(issueRequestId, couponId, code, outcome.expiresAt());
    }

    /** 접수 직후 전송. ACK를 기다리지 않는다. 어떤 실패도 접수 응답을 바꾸지 않는다 */
    private void publishAccepted(CouponIssueRequested event) {
        CompletableFuture<PublishResult> sent;
        try {
            sent = couponIssueEventPublisher.send(event);
        } catch (RuntimeException e) {
            log.debug("발급 요청 이벤트 전송 중 예외가 났다 couponId={}, issueRequestId={}", event.couponId(),
                    event.issueRequestId(), e);
            sent = CompletableFuture.completedFuture(PublishResult.FAILED);
        }
        sent.thenAccept(result -> recordPublished(event, result));
    }

    /** 전송 결과를 지표·로그로 남긴다. producer I/O 스레드에서 돌 수 있다 */
    private void recordPublished(CouponIssueRequested event, PublishResult result) {
        if (!result.acked()) {
            meterRegistry.counter(PUBLISH_FAILURE_METRIC, "result", result.name().toLowerCase(Locale.ROOT)).increment();
            if (publishFailing.compareAndSet(false, true)) {
                log.warn("발급 요청 이벤트를 접수 직후 보내지 못해 재발행 배치에 맡긴다 couponId={}, issueRequestId={}, result={}",
                        event.couponId(), event.issueRequestId(), result);
            } else {
                log.debug("발급 요청 이벤트를 접수 직후 보내지 못했다 couponId={}, issueRequestId={}, result={}",
                        event.couponId(), event.issueRequestId(), result);
            }
            return;
        }
        if (publishFailing.compareAndSet(true, false)) {
            log.info("발급 요청 이벤트를 접수 직후 다시 보내기 시작했다 couponId={}, issueRequestId={}", event.couponId(),
                    event.issueRequestId());
        }
    }

    private static CouponErrorCode errorCodeOf(IssueResponseCode code) {
        return switch (code) {
            case SOLD_OUT -> CouponErrorCode.COUPON_SOLD_OUT;
            case ALREADY_ISSUED -> CouponErrorCode.COUPON_ALREADY_ISSUED;
            case NOT_OPEN -> CouponErrorCode.COUPON_ISSUE_NOT_OPEN;
            case NOT_IN_PERIOD -> CouponErrorCode.COUPON_ISSUE_NOT_IN_PERIOD;
            case REQUEST_ID_CONFLICT -> CouponErrorCode.ISSUE_REQUEST_ID_CONFLICT;
            case ERROR -> CouponErrorCode.COUPON_ISSUE_FAILED;
            case ACCEPTED, ALREADY_ACCEPTED -> throw new IllegalArgumentException("접수된 결과는 오류가 아니다: " + code);
        };
    }

    /**
     * 발급 요청 ID 형식: 1~64자 영문·숫자·{@code _}·{@code -}. 클라이언트가 만든 멱등 키라 Redis Hash field와 DB PK(100자)에 그대로 쓴다.
     */
    static final class IssueRequestIds {

        private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

        private IssueRequestIds() {
        }

        static void requireValid(String issueRequestId) {
            if (issueRequestId == null || !FORMAT.matcher(issueRequestId).matches()) {
                throw new CouponException(CouponErrorCode.INVALID_ISSUE_REQUEST_ID);
            }
        }
    }
}
