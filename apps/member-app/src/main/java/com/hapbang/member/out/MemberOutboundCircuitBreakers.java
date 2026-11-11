package com.hapbang.member.out;

import java.util.function.Supplier;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.hapbang.shared.appearance.out.AppearanceApiCallException;
import com.hapbang.shared.lecture.out.LectureApiCallException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * 회원이 다른 앱을 부르는 호출의 circuit breaker (#654, AGENTS.md 2장). shared Client는 고치지 않고 회원 쪽에서 감싼다
 * (지갑 OutboundCircuitBreakers #562와 같은 방식). 지갑 호출은 shared BillingApiClient에 이미 들어 있어 여기서 감싸지 않는다.
 * <ul>
 *     <li>회로는 상대 앱마다 하나다: {@code appearance}(받은 출연 리뷰 목록 #555 + 탈퇴 확인), {@code lecture}(탈퇴 확인).
 *     같은 앱의 호출을 하나로 묶어, 드물게 부르는 탈퇴 확인도 자주 부르는 리뷰 목록이 감지한 장애로 바로 실패한다.</li>
 *     <li>shared {@code *ApiCallException}의 {@code UNAVAILABLE}(5xx·시간 초과·연결 실패·읽을 수 없는 응답)만 실패로 센다.
 *     {@code REJECTED}(4xx: 내부 API 키·계약 불일치)는 상대가 정상으로 판단한 것이라 성공으로 센다.</li>
 *     <li>회로가 열려 있으면 호출하지 않고 {@link CallNotPermittedException}을 던진다(요청을 보내지 않은 것이 확실하다).
 *     열린 회로는 시간이 지나면 스스로 HALF_OPEN이 된다.</li>
 *     <li>지표: {@code resilience4j.circuitbreaker.*}({@code name} 태그)를 Prometheus로 낸다.</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(MemberOutboundResilienceProperties.class)
public class MemberOutboundCircuitBreakers implements MeterBinder {

    /** 회로 이름 = 상대 앱 */
    public enum Target {
        APPEARANCE("appearance"),
        LECTURE("lecture");

        private final String circuitName;

        Target(String circuitName) {
            this.circuitName = circuitName;
        }

        public String circuitName() {
            return circuitName;
        }
    }

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public MemberOutboundCircuitBreakers(MemberOutboundResilienceProperties properties) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .slowCallDurationThreshold(properties.slowCallDuration())
                .slowCallRateThreshold(properties.slowCallRateThreshold())
                .waitDurationInOpenState(properties.openDuration())
                .permittedNumberOfCallsInHalfOpenState(properties.halfOpenCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(MemberOutboundCircuitBreakers::unavailable)
                .build();
        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(config);
        for (Target target : Target.values()) {
            circuitBreakerRegistry.circuitBreaker(target.circuitName());
        }
    }

    /** 설정 없이 쓰는 기본값 (테스트에서 직접 만들 때) */
    public static MemberOutboundCircuitBreakers defaults() {
        return new MemberOutboundCircuitBreakers(MemberOutboundResilienceProperties.defaults());
    }

    /** 회로 안에서 호출한다. 회로가 열려 있으면 호출하지 않고 {@link CallNotPermittedException}을 던진다 */
    public <T> T call(Target target, Supplier<T> call) {
        return circuitBreaker(target).executeSupplier(call);
    }

    public CircuitBreaker circuitBreaker(Target target) {
        return circuitBreakerRegistry.circuitBreaker(target.circuitName());
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry).bindTo(registry);
    }

    /** 상대가 처리하지 못했거나 결과를 모르는 실패인지(5xx·시간 초과·연결 실패·읽을 수 없는 응답) */
    static boolean unavailable(Throwable exception) {
        return switch (exception) {
            case AppearanceApiCallException e -> e.getReason() == AppearanceApiCallException.Reason.UNAVAILABLE;
            case LectureApiCallException e -> e.getReason() == LectureApiCallException.Reason.UNAVAILABLE;
            default -> false;
        };
    }
}
