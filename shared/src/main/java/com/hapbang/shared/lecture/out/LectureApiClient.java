package com.hapbang.shared.lecture.out;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
        return call(() -> restClient.get()
                .uri(builder -> builder.path(EARNINGS_SUMMARY_PATH).queryParam("userId", sellerUserId).build())
                .retrieve()
                .body(SUMMARY));
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
        return call(() -> restClient.get()
                .uri(builder -> {
                    builder.path(EARNINGS_PATH).queryParam("userId", sellerUserId);
                    statuses.forEach(status -> builder.queryParam("status", status.name()));
                    return builder.queryParam("page", page).queryParam("size", size).build();
                })
                .retrieve()
                .body(PAGE));
    }

    private <T> T call(Supplier<Body<T>> request) {
        Body<T> body;
        try {
            body = request.get();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            LectureApiCallException.Reason reason = e.getStatusCode().is5xxServerError()
                    ? LectureApiCallException.Reason.UNAVAILABLE
                    : LectureApiCallException.Reason.REJECTED;
            throw new LectureApiCallException(reason, status, problemCode(e),
                    "강의 내부 API가 " + status + "로 응답했습니다.", e);
        } catch (ResourceAccessException e) {
            throw new LectureApiCallException(LectureApiCallException.Reason.UNAVAILABLE, 0, null,
                    "강의 내부 API에 연결하지 못했거나 응답 시간이 지났습니다.", e);
        }
        if (body == null || body.data() == null) {
            throw new LectureApiCallException(LectureApiCallException.Reason.UNAVAILABLE, 200, null,
                    "강의 내부 API 응답에 data가 없습니다.", null);
        }
        return body.data();
    }

    private static String problemCode(RestClientResponseException e) {
        try {
            Map<String, Object> problem = e.getResponseBodyAs(PROBLEM);
            return problem == null ? null : Objects.toString(problem.get("code"), null);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** 강의 성공 응답(global RsData)의 모양. shared는 global에 의존하지 않아 같은 필드로 읽는다 */
    record Body<T>(int statusCode, String message, T data) {
    }
}
