package com.hapbang.shared.billing.out;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 지갑 내부 API Client 설정 (hapbang.clients.billing.*, D62). 호출하는 앱마다 billing-app 주소를 준다.
 *
 * @param baseUrl        billing-app 주소 (예: {@code http://localhost:8082})
 * @param connectTimeout 연결 제한 시간. 기본 2초
 * @param readTimeout    응답 제한 시간. 기본 5초
 * @param resilience     Circuit Breaker·짧은 Retry·TimeLimiter (hapbang.clients.billing.resilience.*, #411)
 */
@ConfigurationProperties("hapbang.clients.billing")
public record BillingClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout,
                                      Resilience resilience) {

    // 생성자가 둘이라 설정 바인딩에 쓸 생성자를 밝힌다
    @ConstructorBinding
    public BillingClientProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("hapbang.clients.billing.base-url 설정이 필요합니다.");
        }
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
        resilience = resilience == null ? Resilience.defaults() : resilience;
    }

    /** 회복성 설정은 기본값을 쓴다 */
    public BillingClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        this(baseUrl, connectTimeout, readTimeout, null);
    }

    /**
     * 지갑 Client 회복성 설정 (#411, {@code docs/billing/CONTEXT.md} 2장).
     *
     * @param circuitBreaker Circuit Breaker
     * @param retry          RETRYABLE에만 거는 짧은 Retry
     * @param timeLimit      시도 한 번의 전체 상한. 소켓 timeout(연결 + 응답)이 먼저 끝나야 한다. 기본 8초
     */
    public record Resilience(CircuitBreaker circuitBreaker, Retry retry, Duration timeLimit) {

        public Resilience {
            circuitBreaker = circuitBreaker == null ? CircuitBreaker.defaults() : circuitBreaker;
            retry = retry == null ? Retry.defaults() : retry;
            timeLimit = timeLimit == null ? Duration.ofSeconds(8) : timeLimit;
        }

        static Resilience defaults() {
            return new Resilience(null, null, null);
        }
    }

    /**
     * count 기반 Circuit Breaker.
     *
     * @param slidingWindowSize      최근 몇 번의 호출로 계산할지. 기본 20
     * @param minimumNumberOfCalls   계산을 시작할 최소 호출 수. 기본 10
     * @param failureRateThreshold   회로를 여는 실패율(%). 기본 50
     * @param slowCallDuration       느린 호출로 보는 시간. 기본 4초
     * @param slowCallRateThreshold  회로를 여는 느린 호출 비율(%). 기본 50
     * @param openDuration           OPEN 유지 시간. 기본 30초
     * @param halfOpenCalls          HALF_OPEN에서 허용할 호출 수. 기본 3
     */
    public record CircuitBreaker(Integer slidingWindowSize, Integer minimumNumberOfCalls,
                                 Float failureRateThreshold, Duration slowCallDuration,
                                 Float slowCallRateThreshold, Duration openDuration, Integer halfOpenCalls) {

        public CircuitBreaker {
            slidingWindowSize = slidingWindowSize == null ? 20 : slidingWindowSize;
            minimumNumberOfCalls = minimumNumberOfCalls == null ? 10 : minimumNumberOfCalls;
            failureRateThreshold = failureRateThreshold == null ? 50f : failureRateThreshold;
            slowCallDuration = slowCallDuration == null ? Duration.ofSeconds(4) : slowCallDuration;
            slowCallRateThreshold = slowCallRateThreshold == null ? 50f : slowCallRateThreshold;
            openDuration = openDuration == null ? Duration.ofSeconds(30) : openDuration;
            halfOpenCalls = halfOpenCalls == null ? 3 : halfOpenCalls;
        }

        static CircuitBreaker defaults() {
            return new CircuitBreaker(null, null, null, null, null, null, null);
        }
    }

    /**
     * 짧은 Retry. 대기는 {@code initialWait × multiplier^(n-1)}에 지터를 더한다.
     *
     * @param maxAttempts 첫 호출을 포함한 최대 시도 수. 기본 3
     * @param initialWait 첫 재시도 전 대기. 기본 200ms
     * @param multiplier  대기 증가 배수. 기본 2
     * @param jitter      대기에 더하는 무작위 비율(0.2면 ±20%). 기본 0.2
     */
    public record Retry(Integer maxAttempts, Duration initialWait, Double multiplier, Double jitter) {

        public Retry {
            maxAttempts = maxAttempts == null ? 3 : maxAttempts;
            initialWait = initialWait == null ? Duration.ofMillis(200) : initialWait;
            multiplier = multiplier == null ? 2.0 : multiplier;
            jitter = jitter == null ? 0.2 : jitter;
        }

        static Retry defaults() {
            return new Retry(null, null, null, null);
        }
    }
}
