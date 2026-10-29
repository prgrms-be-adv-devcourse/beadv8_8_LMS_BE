package com.hapbang.shared.billing.out;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Objects;

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
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;
import com.hapbang.shared.internal.InternalApiHeaders;

/**
 * 지갑 내부 HTTP API Client (D62, D64, #272). 강의·출연은 지갑 Java 타입 대신 이 Client로 HP를 보류·반환·지급한다.
 * URI·내부 API 키·응답 감싸기(RsData)·실패 분류를 감춘다.
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
    private static final ParameterizedTypeReference<Map<String, Object>> PROBLEM =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

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

    private <T> BillingCallResult<T> post(String path, Object command, ParameterizedTypeReference<Body<T>> type) {
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
