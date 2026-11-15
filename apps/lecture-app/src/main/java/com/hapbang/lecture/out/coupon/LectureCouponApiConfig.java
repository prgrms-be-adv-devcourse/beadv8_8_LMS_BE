package com.hapbang.lecture.out.coupon;

import java.io.IOException;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.hapbang.shared.internal.InternalApiHeaders;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 쿠폰 사용 내부 API HTTP Interface({@link LectureCouponInternalApi}) 프록시.
 * <ul>
 *     <li>주소는 {@code hapbang.coupon.base-url}이다. 없으면 같은 서버의 내부 API 포트다: 강의 앱은
 *     {@code hapbang.internal-api.port}가 있으면(운영) 내부 API를 그 포트에서만 받고, 없으면 {@code server.port}에서 받는다.</li>
 *     <li>제한 시간은 {@code lecture.coupon-client.connect-timeout}·{@code read-timeout}({@link LectureCouponClientProperties})이다.
 *     circuit breaker는 어댑터({@link CouponApiClient})가 건다.</li>
 *     <li>모든 요청에 내부 API 키 헤더({@link InternalApiHeaders#API_KEY}, 값 {@code hapbang.internal-api.key})를 붙인다.</li>
 *     <li>오류 상태는 여기서 예외로 바꾸고, 어댑터가 결과({@link CouponCallResult})로 바꾼다.
 *     4xx는 쿠폰이 판단한 거절({@link CouponRejectedResponse})이고 쿠폰 {@code code}·{@code memberCouponId}·{@code detail}을 보존한다.
 *     다만 408·425·429는 쿠폰이 요청을 처리하지 않았다는 응답({@link CouponNotProcessedResponse})이다(지갑 호출과 같은 기준).
 *     그 밖의 오류 상태(5xx 등)는 쿠폰이 커밋한 뒤 응답을 만들다 실패했을 수도 있어 결과 불명({@link CouponUnknownResponse})이다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LectureCouponClientProperties.class)
public class LectureCouponApiConfig {

    /** 요청을 처리하지 않았다는 응답(시간 초과, 너무 이름, 요청 한도 초과). 다시 보내면 된다 */
    private static final Set<Integer> NOT_PROCESSED_STATUSES = Set.of(408, 425, 429);

    @Bean
    LectureCouponInternalApi lectureCouponInternalApi(RestClient.Builder restClientBuilder,
            @Value("${hapbang.coupon.base-url:http://localhost:${hapbang.internal-api.port:${server.port:8080}}}")
            String baseUrl,
            @Value("${hapbang.internal-api.key}") String internalApiKey,
            LectureCouponClientProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return create(restClientBuilder.clone().requestFactory(requestFactory), baseUrl, internalApiKey);
    }

    /** 빈과 같은 설정의 프록시. 테스트가 컨텍스트 없이 만들 때도 쓴다(제한 시간은 넘긴 Builder의 request factory를 따른다) */
    public static LectureCouponInternalApi create(RestClient.Builder restClientBuilder, String baseUrl,
            String internalApiKey) {
        RestClient restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(InternalApiHeaders.API_KEY, internalApiKey)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw statusFailure(response);
                })
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build()
                .createClient(LectureCouponInternalApi.class);
    }

    private static RuntimeException statusFailure(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        JsonNode problem = problem(response);
        String code = text(problem, "code");
        if (NOT_PROCESSED_STATUSES.contains(status)) {
            return new CouponNotProcessedResponse(status, code);
        }
        if (response.getStatusCode().is4xxClientError()) {
            JsonNode memberCouponId = problem == null ? null : problem.get("memberCouponId");
            return new CouponRejectedResponse(status, code,
                    memberCouponId == null || !memberCouponId.isIntegralNumber() ? null : memberCouponId.asLong(),
                    text(problem, "detail"));
        }
        return new CouponUnknownResponse(status, code);
    }

    /** 오류 응답 본문(ProblemDetail). 본문이 없거나 JSON이 아니면 null */
    private static JsonNode problem(ClientHttpResponse response) {
        try {
            byte[] body = response.getBody().readAllBytes();
            return body.length == 0 ? null : JsonMapper.shared().readTree(body);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static String text(JsonNode problem, String field) {
        JsonNode value = problem == null ? null : problem.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    /** 쿠폰이 4xx로 거절했다(408·425·429 제외). 쿠폰이 정상으로 판단한 것이라 회로 실패로 세지 않는다 */
    static final class CouponRejectedResponse extends RuntimeException {

        private final int status;
        private final String code;
        private final Long memberCouponId;
        private final String detail;

        CouponRejectedResponse(int status, String code, Long memberCouponId, String detail) {
            super("쿠폰 내부 API가 " + status + "로 거절했습니다(code=" + code + ").", null, false, false);
            this.status = status;
            this.code = code;
            this.memberCouponId = memberCouponId;
            this.detail = detail;
        }

        <T> CouponCallResult.Rejected<T> toResult() {
            return new CouponCallResult.Rejected<>(status, code, memberCouponId, detail);
        }
    }

    /** 쿠폰이 요청을 처리하지 않았다고 답했다(408·425·429) */
    static final class CouponNotProcessedResponse extends RuntimeException {

        CouponNotProcessedResponse(int status, String code) {
            super("쿠폰 내부 API가 요청을 처리하지 않았습니다(" + status + ", code=" + code + ").", null, false, false);
        }
    }

    /** 쿠폰이 4xx가 아닌 오류 상태(5xx 등)로 답했다. 처리했는지 모른다 */
    static final class CouponUnknownResponse extends RuntimeException {

        private final int status;

        CouponUnknownResponse(int status, String code) {
            super("쿠폰 내부 API가 " + status + "로 응답했습니다(code=" + code + ").", null, false, false);
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}
