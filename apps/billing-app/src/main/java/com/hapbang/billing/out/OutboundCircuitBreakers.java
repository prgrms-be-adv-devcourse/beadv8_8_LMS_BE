package com.hapbang.billing.out;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.hapbang.shared.appearance.out.AppearanceApiCallException;
import com.hapbang.shared.lecture.out.LectureApiCallException;
import com.hapbang.shared.member.out.MemberApiCallException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * 지갑이 다른 서비스를 부르는 호출의 circuit breaker (#562, BD-134). shared Client는 고치지 않고 지갑 쪽에서 감싼다.
 * <ul>
 *     <li>회로는 상대마다 하나다: {@code lecture}·{@code appearance}(수익 조회), {@code member}(회원 Backfill), {@code toss}(충전·환불).</li>
 *     <li>강의·출연·회원: shared {@code *ApiCallException}의 {@code UNAVAILABLE}(5xx·시간 초과·연결 실패·읽을 수 없는 응답)만 실패로 센다.
 *     {@code REJECTED}(4xx: 내부 API 키·계약 불일치)는 상대가 정상으로 판단한 것이라 성공으로 센다(shared BillingApiClient와 같은 기준).</li>
 *     <li>TossPayments: 결과 {@code Unknown}만 실패로 센다. {@code Rejected}(카드 거절 등 4xx 판단)·{@code NotFound}는 성공이다.</li>
 *     <li>수익 조회만 연결 자체가 안 됐을 때(요청이 상대에 닿지 않음) 한 번 더 보낸다. 회원 Backfill·TossPayments는 다시 보내지 않는다
 *     (Backfill은 관리자가 다시 실행하고, TossPayments의 결과 불명은 복구 배치가 같은 멱등키로 확인한다).</li>
 *     <li>열린 회로는 시간이 지나면 스스로 HALF_OPEN이 된다. 복구 배치는 열린 동안 호출하지 않으므로 호출을 기다리지 않게 한다.</li>
 *     <li>지표: {@code resilience4j.circuitbreaker.*}·{@code resilience4j.retry.calls}({@code name} 태그)를 Prometheus로 낸다.</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(OutboundResilienceProperties.class)
public class OutboundCircuitBreakers implements MeterBinder {

    /** 회로 이름 = 상대 */
    public enum Target {
        LECTURE("lecture"),
        APPEARANCE("appearance"),
        MEMBER("member"),
        TOSS("toss");

        private final String circuitName;

        Target(String circuitName) {
            this.circuitName = circuitName;
        }

        public String circuitName() {
            return circuitName;
        }
    }

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final Retry earningRetry;

    public OutboundCircuitBreakers(OutboundResilienceProperties properties) {
        CircuitBreakerConfig internalApi = base(properties)
                .recordException(OutboundCircuitBreakers::unavailable)
                .build();
        CircuitBreakerConfig toss = base(properties)
                .recordResult(result -> result instanceof TossResult.Unknown)
                .build();
        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(internalApi);
        circuitBreakerRegistry.circuitBreaker(Target.LECTURE.circuitName());
        circuitBreakerRegistry.circuitBreaker(Target.APPEARANCE.circuitName());
        circuitBreakerRegistry.circuitBreaker(Target.MEMBER.circuitName());
        circuitBreakerRegistry.circuitBreaker(Target.TOSS.circuitName(), toss);
        this.retryRegistry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(properties.earningRetryWait())
                .retryOnException(OutboundCircuitBreakers::notConnected)
                .build());
        this.earningRetry = retryRegistry.retry("earning");
    }

    /** 설정 없이 쓰는 기본값 (테스트에서 직접 만들 때) */
    public static OutboundCircuitBreakers defaults() {
        return new OutboundCircuitBreakers(OutboundResilienceProperties.defaults());
    }

    /**
     * 회로 안에서 호출한다. 회로가 열려 있으면 호출하지 않고 {@link CallNotPermittedException}을 던진다(요청을 보내지 않은 것이 확실하다).
     */
    public <T> T call(Target target, Supplier<T> call) {
        return circuitBreaker(target).executeSupplier(call);
    }

    /** 수익 조회: 회로 안에서 호출하고, 연결 자체가 안 됐으면 한 번 더 보낸다. 열린 회로는 다시 보내지 않는다 */
    public <T> T callWithConnectRetry(Target target, Supplier<T> call) {
        return Retry.decorateSupplier(earningRetry, CircuitBreaker.decorateSupplier(circuitBreaker(target), call)).get();
    }

    /** 회로가 열려 있는지(OPEN·FORCED_OPEN). HALF_OPEN은 시험 호출을 받으므로 열린 것으로 보지 않는다 */
    public boolean isOpen(Target target) {
        CircuitBreaker.State state = circuitBreaker(target).getState();
        return state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN;
    }

    public CircuitBreaker circuitBreaker(Target target) {
        return circuitBreakerRegistry.circuitBreaker(target.circuitName());
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry).bindTo(registry);
        TaggedRetryMetrics.ofRetryRegistry(retryRegistry).bindTo(registry);
    }

    private static CircuitBreakerConfig.Builder base(OutboundResilienceProperties properties) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .slowCallDurationThreshold(properties.slowCallDuration())
                .slowCallRateThreshold(properties.slowCallRateThreshold())
                .waitDurationInOpenState(properties.openDuration())
                .permittedNumberOfCallsInHalfOpenState(properties.halfOpenCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(true);
    }

    /** 상대가 처리하지 못했거나 결과를 모르는 실패인지(5xx·시간 초과·연결 실패·읽을 수 없는 응답) */
    static boolean unavailable(Throwable exception) {
        return switch (exception) {
            case LectureApiCallException e -> e.getReason() == LectureApiCallException.Reason.UNAVAILABLE;
            case AppearanceApiCallException e -> e.getReason() == AppearanceApiCallException.Reason.UNAVAILABLE;
            case MemberApiCallException e -> e.getReason() == MemberApiCallException.Reason.UNAVAILABLE;
            default -> false;
        };
    }

    /** 연결 자체가 안 돼 요청이 상대에 닿지 않았는지. 응답 시간 초과는 닿았을 수 있어 넣지 않는다 */
    static boolean notConnected(Throwable exception) {
        if (!unavailable(exception)) {
            return false;
        }
        Predicate<Throwable> connectFailure = cause -> cause instanceof ConnectException
                || cause instanceof UnknownHostException || cause instanceof NoRouteToHostException;
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (connectFailure.test(cause)) {
                return true;
            }
        }
        return false;
    }
}
