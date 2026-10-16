package com.hapbang.shared.lecture.out;

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

import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.lecture.domain.LectureEarningStatus;
import com.hapbang.shared.lecture.dto.LectureEarningPageDto;
import com.hapbang.shared.lecture.dto.LectureEarningSummaryDto;

/**
 * 강의 내부 HTTP API Client (D62, #300). 다른 모듈은 강의 Java 타입 대신 이 Client로 강의를 부른다.
 * URI·내부 API 키·응답 감싸기(RsData)·오류 변환을 감춘다. 실패는 {@link LectureApiCallException}이다.
 * {@code hapbang.clients.lecture.base-url}이 있을 때만 빈이 생긴다(모듈 테스트에는 없다).
 * 응답 시각은 {@code +09:00}이지만 앱 Jackson 설정에 따라 같은 시각의 다른 offset(UTC)으로 읽힐 수 있다.
 * 날짜로 나눌 때는 KST로 바꿔 쓴다.
 */
@Component
@ConditionalOnProperty(prefix = "hapbang.clients.lecture", name = "base-url")
@EnableConfigurationProperties(LectureClientProperties.class)
public class LectureApiClient {

    static final String EARNINGS_PATH = "/api/v1/internal/lectures/earnings";
    static final String EARNINGS_SUMMARY_PATH = EARNINGS_PATH + "/summary";

    private static final ParameterizedTypeReference<Body<LectureEarningSummaryDto>> SUMMARY =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Body<LectureEarningPageDto>> PAGE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Map<String, Object>> PROBLEM =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public LectureApiClient(RestClient.Builder restClientBuilder, LectureClientProperties properties,
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

    /** 판매자의 아직 지급하지 않은 판매 수익 요약 */
    public LectureEarningSummaryDto getEarningSummary(long sellerUserId) {
        return call(restClient.get()
                .uri(builder -> builder.path(EARNINGS_SUMMARY_PATH).queryParam("userId", sellerUserId).build()), SUMMARY);
    }

    /**
     * 판매자의 판매 수익 목록. 상태 변경 최신순이다.
     *
     * @param statuses 비어 있으면 아직 지급하지 않은 수익만
     * @param page     페이지 번호 (0부터)
     * @param size     페이지 크기 (최대 100)
     */
    public LectureEarningPageDto getEarnings(long sellerUserId, Collection<LectureEarningStatus> statuses, int page,
                                             int size) {
        return call(restClient.get()
                .uri(builder -> {
                    builder.path(EARNINGS_PATH).queryParam("userId", sellerUserId);
                    statuses.forEach(status -> builder.queryParam("status", status.name()));
                    return builder.queryParam("page", page).queryParam("size", size).build();
                }), PAGE);
    }

    /**
     * 응답 상태를 잃지 않도록 직접 읽는다. 4xx는 거절, 5xx·읽을 수 없는 본문(깨진 JSON, 계약에 없는 enum 값,
     * 본문 없음)·연결 실패·시간 초과는 호출 불가다. 어떤 실패든 {@link LectureApiCallException}으로 나간다
     */
    private <T> T call(RestClient.RequestHeadersSpec<?> request, ParameterizedTypeReference<Body<T>> type) {
        try {
            return request.exchange((req, response) -> {
                int status = response.getStatusCode().value();
                if (response.getStatusCode().isError()) {
                    LectureApiCallException.Reason reason = response.getStatusCode().is5xxServerError()
                            ? LectureApiCallException.Reason.UNAVAILABLE
                            : LectureApiCallException.Reason.REJECTED;
                    throw new LectureApiCallException(reason, status, problemCode(response),
                            "강의 내부 API가 " + status + "로 응답했습니다.", null);
                }
                Body<T> body;
                try {
                    body = response.bodyTo(type);
                } catch (RuntimeException unreadable) {
                    throw new LectureApiCallException(LectureApiCallException.Reason.UNAVAILABLE, status, null,
                            "강의 내부 API 응답을 읽지 못했습니다.", unreadable);
                }
                if (body == null || body.data() == null) {
                    throw new LectureApiCallException(LectureApiCallException.Reason.UNAVAILABLE, status, null,
                            "강의 내부 API 응답에 data가 없습니다.", null);
                }
                return body.data();
            });
        } catch (LectureApiCallException e) {
            throw e;
        } catch (RestClientException e) {
            throw new LectureApiCallException(LectureApiCallException.Reason.UNAVAILABLE, 0, null,
                    "강의 내부 API에 연결하지 못했거나 응답 시간이 지났습니다.", e);
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

    /** 강의 성공 응답(global RsData)의 모양. shared는 global에 의존하지 않아 같은 필드로 읽는다 */
    record Body<T>(int statusCode, String message, T data) {
    }
}
