package com.hapbang.shared.billing.out;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.hapbang.shared.billing.domain.BillingFailureCode;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.HoldVoidCommand;
import com.hapbang.shared.billing.dto.HoldVoidResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;
import com.hapbang.shared.internal.InternalApiHeaders;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;

/**
 * 지갑 내부 HTTP API Client (D62, D64, #272). 강의·출연은 지갑 Java 타입 대신 이 Client로 HP를 보류·반환·지급한다.
 * URI·내부 API 키·응답 감싸기(RsData)·실패 분류를 감춘다.
 * 호출은 {@code Retry( CircuitBreaker( TimeLimiter( HTTP ) ) )}로 감싼다 (#411, {@code docs/billing/CONTEXT.md} 2장).
 * {@code hapbang.clients.billing.base-url}이 있을 때만 빈이 생긴다.
 */
@Component
@ConditionalOnProperty(prefix = "hapbang.clients.billing", name = "base-url")
@EnableConfigurationProperties(BillingClientProperties.class)
public class BillingApiClient {

    static final String BASE_PATH = "/api/v1/internal/billing";
    static final String HOLDS_PATH = BASE_PATH + "/holds";
    static final String RELEASES_PATH = BASE_PATH + "/releases";
    static final String PAYOUTS_PATH = BASE_PATH + "/payouts";
    static final String PENALTY_SETTLEMENTS_PATH = BASE_PATH + "/penalty-settlements";
    static final String HOLD_VOIDS_PATH = BASE_PATH + "/hold-voids";

    /** 408 Request Timeout, 425 Too Early, 429 Too Many Requests */
    private static final Set<Integer> NOT_PROCESSED_STATUSES = Set.of(408, 425, 429);

    /** Circuit Breaker·Retry 인스턴스 이름. 지갑 하나에 하나다 */
    static final String INSTANCE_NAME = "billing";
    /** 회로가 열려 요청을 보내지 않았을 때의 {@link BillingCallException#getRawCode()} */
    public static final String CIRCUIT_OPEN = "CIRCUIT_OPEN";
    /** 지갑 락 대기 초과(503). 한 지갑의 경합이라 Circuit Breaker 실패로 세지 않는다 */
    private static final String HP_LOCK_TIMEOUT = "HP_LOCK_TIMEOUT";
    private static final int TOO_MANY_REQUESTS = 429;

    private static final ParameterizedTypeReference<Body<HoldResultDto>> HOLD = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<Body<ReleaseResultDto>> RELEASE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Body<PayoutResultDto>> PAYOUT =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Body<PenaltyResultDto>> PENALTY =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Body<HoldVoidResultDto>> HOLD_VOID =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Map<String, Object>> PROBLEM =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;
    // 시간 상한을 걸려면 호출을 다른 스레드에서 돌려야 한다. 동시 호출 수는 배치 크기가 정하므로 풀을 두지 않는다
    private final Executor executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("billing-client-", 0).factory());

    public BillingApiClient(RestClient.Builder restClientBuilder, BillingClientProperties properties,
                            @Value("${hapbang.internal-api.key}") String internalApiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = restClientBuilder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(InternalApiHeaders.API_KEY, internalApiKey)
                .build();

        BillingClientProperties.Resilience resilience = properties.resilience();
        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig(resilience.circuitBreaker()));
        this.retryRegistry = RetryRegistry.of(retryConfig(resilience.retry()));
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
        this.retry = retryRegistry.retry(INSTANCE_NAME);
        this.timeLimiter = TimeLimiter.of(INSTANCE_NAME, TimeLimiterConfig.custom()
                .timeoutDuration(resilience.timeLimit())
                .cancelRunningFuture(true)
                .build());
    }

    private static CircuitBreakerConfig circuitBreakerConfig(BillingClientProperties.CircuitBreaker config) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(config.slidingWindowSize())
                .minimumNumberOfCalls(config.minimumNumberOfCalls())
                .failureRateThreshold(config.failureRateThreshold())
                .slowCallDurationThreshold(config.slowCallDuration())
                .slowCallRateThreshold(config.slowCallRateThreshold())
                .waitDurationInOpenState(config.openDuration())
                .permittedNumberOfCallsInHalfOpenState(config.halfOpenCalls())
                // 배치는 회로가 열려 있으면 호출하지 않으므로(D67) 호출 없이도 대기 뒤 HALF_OPEN으로 넘어가야 한다
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // 지갑이 응답한 업무 실패(Failed)는 결과라 성공으로 센다. 결과를 받지 못한 실패만 센다
                .recordException(e -> e instanceof BillingCallException)
                .ignoreException(BillingApiClient::notWalletFailure)
                .build();
    }

    /** 429(과부하 신호)와 락 대기 초과(한 지갑의 경합)는 지갑 장애가 아니다 */
    private static boolean notWalletFailure(Throwable exception) {
        return exception instanceof BillingCallException e
                && (e.getHttpStatus() == TOO_MANY_REQUESTS || HP_LOCK_TIMEOUT.equals(e.getRawCode()));
    }

    private static RetryConfig retryConfig(BillingClientProperties.Retry config) {
        return RetryConfig.custom()
                .maxAttempts(config.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(config.initialWait(),
                        config.multiplier(), config.jitter()))
                // 지갑이 처리하지 않은 것이 확실한 실패만 바로 다시 보낸다. 결과 불명은 호출한 쪽 작업 재시도에 맡긴다
                .retryOnException(e -> e instanceof BillingCallException b
                        && b.getKind() == BillingCallFailureKind.RETRYABLE)
                .build();
    }

    /** HP 보류. 참조 대상(유형 + ID)이 멱등 키다 */
    public BillingCallResult<HoldResultDto> hold(HoldCommand command) {
        return post(HOLDS_PATH, command, HOLD);
    }

    /** 보류 HP 반환. 반환 요청 ID가 멱등 키다. 금액이 없으면 남은 보류 전액이다 */
    public BillingCallResult<ReleaseResultDto> release(ReleaseCommand command) {
        return post(RELEASES_PATH, command, RELEASE);
    }

    /** 보류 HP를 받는 회원에게 지급. 지급 요청 ID가 멱등 키다. 금액이 없으면 남은 보류 전액이다 */
    public BillingCallResult<PayoutResultDto> payout(PayoutCommand command) {
        return post(PAYOUTS_PATH, command, PAYOUT);
    }

    /** 출연 취소 위약금 정산(보류 일부 반환 + 일부 지급). 취소 ID가 멱등 키다 */
    public BillingCallResult<PenaltyResultDto> settlePenalty(PenaltyCommand command) {
        return post(PENALTY_SETTLEMENTS_PATH, command, PENALTY);
    }

    /**
     * 보류 무효화(#479). 참조 대상의 보류를 더는 쓰지 않을 때 부른다: 남은 보류는 반환하고, 아직 보류가 없으면 앞으로 그 참조 대상에
     * 보류가 생기지 않게 한다(뒤늦은 보류는 {@code HOLD_VOIDED}). 참조 대상이 멱등 키다
     */
    public BillingCallResult<HoldVoidResultDto> voidHold(HoldVoidCommand command) {
        return post(HOLD_VOIDS_PATH, command, HOLD_VOID);
    }

    private <T> BillingCallResult<T> post(String path, Object command, ParameterizedTypeReference<Body<T>> type) {
        Supplier<BillingCallResult<T>> call = () -> withinTimeLimit(() -> send(path, command, type));
        call = CircuitBreaker.decorateSupplier(circuitBreaker, call);
        call = Retry.decorateSupplier(retry, call);
        try {
            return call.get();
        } catch (CallNotPermittedException e) {
            // 요청을 보내지 않았으니 지갑이 처리하지 않은 것이 확실하다 (D66)
            throw new BillingCallException(BillingCallFailureKind.RETRYABLE, 0, CIRCUIT_OPEN,
                    "지갑 내부 API 회로가 열려 요청을 보내지 않았습니다.", e);
        }
    }

    /**
     * 회로가 열려 지금 호출하면 요청을 보내지 않고 {@link #CIRCUIT_OPEN}으로 실패하는지 (D67).
     * HP 작업 배치는 이것이 {@code true}면 작업을 선점하지 않고 회차를 건너뛴다. 시험 중(HALF_OPEN)은 {@code false}다.
     */
    public boolean isCircuitOpen() {
        CircuitBreaker.State state = circuitBreaker.getState();
        return state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN;
    }

    /** 시도 한 번의 전체 상한. 소켓 timeout이 먼저 끝나는 것이 정상이고, 이것은 그것이 동작하지 않을 때의 안전망이다 */
    private <T> BillingCallResult<T> withinTimeLimit(Supplier<BillingCallResult<T>> send) {
        try {
            return timeLimiter.executeFutureSupplier(() -> CompletableFuture.supplyAsync(send, executor));
        } catch (TimeoutException e) {
            // 상한이 지나도 보낸 요청은 뒤에서 계속 돌아 지갑이 커밋할 수 있다
            throw new BillingCallException(BillingCallFailureKind.UNKNOWN_RESULT, 0, null,
                    "지갑 내부 API 응답이 제한 시간을 넘었습니다.", e);
        } catch (ExecutionException e) {
            throw unchecked(e.getCause() == null ? e : e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BillingCallException(BillingCallFailureKind.UNKNOWN_RESULT, 0, null,
                    "지갑 내부 API 응답을 기다리다 중단됐습니다.", e);
        } catch (Exception e) {
            throw unchecked(e);
        }
    }

    private static RuntimeException unchecked(Throwable exception) {
        if (exception instanceof RuntimeException runtime) {
            return runtime;
        }
        if (exception instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(exception);
    }

    private <T> BillingCallResult<T> send(String path, Object command, ParameterizedTypeReference<Body<T>> type) {
        try {
            return exchange(path, command, type);
        } catch (BillingCallException e) {
            throw e;
        } catch (RestClientException e) {
            // 연결을 맺기 전에 실패했을 때만 요청이 지갑에 닿지 않은 것이 확실하다.
            // 연결 시간 초과는 응답 시간 초과와 같은 예외라 구분하지 않고 결과 불명으로 둔다 (D64)
            BillingCallFailureKind kind = notSent(e) ? BillingCallFailureKind.RETRYABLE
                    : BillingCallFailureKind.UNKNOWN_RESULT;
            throw new BillingCallException(kind, 0, null, "지갑 내부 API의 응답을 받지 못했습니다.", e);
        }
    }

    private static boolean notSent(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException) {
                return true;
            }
        }
        return false;
    }

    private <T> BillingCallResult<T> exchange(String path, Object command, ParameterizedTypeReference<Body<T>> type) {
        return restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(command)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    // 요청을 처리하지 않았다는 응답(시간 초과, 너무 이름, 요청 한도 초과)은 다시 보내면 된다 (D64)
                    if (NOT_PROCESSED_STATUSES.contains(status)) {
                        throw new BillingCallException(BillingCallFailureKind.RETRYABLE, status,
                                Objects.toString(problem(response).get("code"), null),
                                "지갑 내부 API가 요청을 처리하지 않았습니다(" + status + ").", null);
                    }
                    if (response.getStatusCode().is4xxClientError()) {
                        Map<String, Object> problem = problem(response);
                        String rawCode = Objects.toString(problem.get("code"), null);
                        return new BillingCallResult.Failed<>(BillingFailureCode.from(rawCode),
                                Objects.toString(problem.get("detail"), null), status, rawCode);
                    }
                    // 5xx 등: 지갑이 커밋한 뒤 응답을 만들다 실패했을 수도 있어 코드가 있어도 결과 불명이다 (D64)
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new BillingCallException(BillingCallFailureKind.UNKNOWN_RESULT, status,
                                Objects.toString(problem(response).get("code"), null),
                                "지갑 내부 API가 " + status + "로 응답했습니다.", null);
                    }
                    // 읽을 수 없는 성공 응답도 지갑이 처리했는지 알 수 없다
                    Body<T> body;
                    try {
                        body = response.bodyTo(type);
                    } catch (RuntimeException unreadable) {
                        throw unknownResponse(status, "지갑 내부 API 응답을 읽지 못했습니다.", unreadable);
                    }
                    if (body == null || body.data() == null) {
                        throw unknownResponse(status, "지갑 내부 API 응답에 data가 없습니다.", null);
                    }
                    return new BillingCallResult.Succeeded<>(body.data());
                });
    }

    CircuitBreakerRegistry circuitBreakerRegistry() {
        return circuitBreakerRegistry;
    }

    RetryRegistry retryRegistry() {
        return retryRegistry;
    }

    CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    private static BillingCallException unknownResponse(int status, String message, Throwable cause) {
        return new BillingCallException(BillingCallFailureKind.UNKNOWN_RESULT, status, null, message, cause);
    }

    /** 오류 응답 본문(ProblemDetail). 본문이 없거나 읽을 수 없으면 빈 Map이다 */
    private static Map<String, Object> problem(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            Map<String, Object> problem = response.bodyTo(PROBLEM);
            return problem == null ? Map.of() : problem;
        } catch (RuntimeException unreadable) {
            return Map.of();
        }
    }

    /** 지갑 성공 응답(RsData)의 모양. shared는 앱에 의존하지 않아 같은 필드로 읽는다 */
    record Body<T>(int statusCode, String message, T data) {
    }
}
