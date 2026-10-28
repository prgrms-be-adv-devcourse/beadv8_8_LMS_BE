package com.hapbang.shared.appearance.out;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.hapbang.shared.appearance.domain.AppearanceEarningStatus;
import com.hapbang.shared.appearance.domain.AppearanceEarningType;
import com.hapbang.shared.appearance.dto.AppearanceEarningPageDto;
import com.hapbang.shared.appearance.dto.AppearanceEarningSummaryDto;
import com.hapbang.shared.internal.InternalApiHeaders;

/**
 * 출연 내부 HTTP API Client (D62, #301). 다른 모듈은 출연 Java 타입 대신 이 Client로 출연을 부른다.
 * URI·내부 API 키·응답 감싸기(RsData)·오류 변환을 감춘다. 실패는 {@link AppearanceApiCallException}이다.
 * {@code hapbang.clients.appearance.base-url}이 있을 때만 빈이 생긴다(모듈 테스트에는 없다).
 * 응답 시각은 offset 없는 KST {@code LocalDateTime}이다 (강의는 {@code +09:00} offset).
 */
@Component
@ConditionalOnProperty(prefix = "hapbang.clients.appearance", name = "base-url")
@EnableConfigurationProperties(AppearanceClientProperties.class)
public class AppearanceApiClient {

    static final String EARNINGS_PATH = "/api/v1/internal/appearances/earnings";
    static final String EARNINGS_SUMMARY_PATH = EARNINGS_PATH + "/summary";

    private static final ParameterizedTypeReference<Body<AppearanceEarningSummaryDto>> SUMMARY =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Body<AppearanceEarningPageDto>> PAGE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Map<String, Object>> PROBLEM =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public AppearanceApiClient(RestClient.Builder restClientBuilder, AppearanceClientProperties properties,
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

    /** 게스트의 아직 받지 못한 출연 수익 요약 (출연료·위약금 모두) */
    public AppearanceEarningSummaryDto getEarningSummary(long guestUserId) {
        return getEarningSummary(guestUserId, null);
    }

    /**
     * 게스트의 아직 받지 못한 출연 수익 요약.
     *
     * @param earningType 종류. {@code null}이면 둘 다
     */
    public AppearanceEarningSummaryDto getEarningSummary(long guestUserId, AppearanceEarningType earningType) {
        return call(restClient.get()
                .uri(builder -> {
                    builder.path(EARNINGS_SUMMARY_PATH).queryParam("userId", guestUserId);
                    if (earningType != null) {
                        builder.queryParam("earningType", earningType.name());
                    }
                    return builder.build();
                }), SUMMARY);
    }

    /** 게스트의 출연 수익 목록 (출연료·위약금 모두). {@link #getEarnings(long, Collection, AppearanceEarningType, int, int)} */
    public AppearanceEarningPageDto getEarnings(long guestUserId, Collection<AppearanceEarningStatus> statuses, int page,
                                                int size) {
        return getEarnings(guestUserId, statuses, null, page, size);
    }

    /**
     * 게스트의 출연 수익 목록. 상태 변경 최신순이다.
     *
     * @param statuses    비어 있거나 {@code null}이면 아직 받지 못한 수익만
     * @param earningType 종류. {@code null}이면 둘 다
     * @param page        페이지 번호 (0부터)
     * @param size        페이지 크기 (최대 100)
     */
    public AppearanceEarningPageDto getEarnings(long guestUserId, Collection<AppearanceEarningStatus> statuses,
                                                AppearanceEarningType earningType, int page, int size) {
        return call(restClient.get()
                .uri(builder -> {
                    builder.path(EARNINGS_PATH).queryParam("userId", guestUserId);
                    if (statuses != null) {
                        statuses.forEach(status -> builder.queryParam("status", status.name()));
                    }
                    if (earningType != null) {
                        builder.queryParam("earningType", earningType.name());
                    }
                    return builder.queryParam("page", page).queryParam("size", size).build();
                }), PAGE);
    }

    /**
     * 응답 상태를 잃지 않도록 직접 읽는다. 4xx는 거절, 5xx·읽을 수 없는 본문(깨진 JSON, 계약에 없는 enum 값,
     * 본문 없음)·연결 실패·시간 초과는 호출 불가다. 어떤 실패든 {@link AppearanceApiCallException}으로 나간다
     */
    private <T> T call(RestClient.RequestHeadersSpec<?> request, ParameterizedTypeReference<Body<T>> type) {
        try {
            return request.exchange((req, response) -> {
                int status = response.getStatusCode().value();
                if (response.getStatusCode().isError()) {
                    AppearanceApiCallException.Reason reason = response.getStatusCode().is5xxServerError()
                            ? AppearanceApiCallException.Reason.UNAVAILABLE
                            : AppearanceApiCallException.Reason.REJECTED;
                    throw new AppearanceApiCallException(reason, status, problemCode(response),
                            "출연 내부 API가 " + status + "로 응답했습니다.", null);
                }
                Body<T> body;
                try {
                    body = response.bodyTo(type);
                } catch (RuntimeException unreadable) {
                    throw new AppearanceApiCallException(AppearanceApiCallException.Reason.UNAVAILABLE, status, null,
                            "출연 내부 API 응답을 읽지 못했습니다.", unreadable);
                }
                if (body == null || body.data() == null) {
                    throw new AppearanceApiCallException(AppearanceApiCallException.Reason.UNAVAILABLE, status, null,
                            "출연 내부 API 응답에 data가 없습니다.", null);
                }
                return body.data();
            });
        } catch (AppearanceApiCallException e) {
            throw e;
        } catch (RestClientException e) {
            throw new AppearanceApiCallException(AppearanceApiCallException.Reason.UNAVAILABLE, 0, null,
                    "출연 내부 API에 연결하지 못했거나 응답 시간이 지났습니다.", e);
        }
    }

    private static String problemCode(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            Map<String, Object> problem = response.bodyTo(PROBLEM);
            return problem == null ? null : Objects.toString(problem.get("code"), null);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** 출연 성공 응답(global RsData)의 모양. shared는 global에 의존하지 않아 같은 필드로 읽는다 */
    record Body<T>(int statusCode, String message, T data) {
    }
}
