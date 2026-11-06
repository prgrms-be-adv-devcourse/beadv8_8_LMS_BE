package com.hapbang.coupon.out.lecture;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.hapbang.coupon.app.port.LectureCatalogUnavailableException;
import com.hapbang.shared.internal.InternalApiHeaders;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 강의 조회 내부 API HTTP Interface({@link CouponLectureInternalApi}) 프록시.
 * <ul>
 *     <li>주소는 {@code hapbang.coupon.lecture-base-url}이다. 없으면 같은 서버의 내부 API 포트다: 강의 앱은
 *     {@code hapbang.internal-api.port}가 있으면(운영) 내부 API를 그 포트에서만 받고, 없으면 {@code server.port}에서 받는다.</li>
 *     <li>제한 시간은 앱의 {@code spring.http.clients.*}(자동 설정된 {@link RestClient.Builder})를 따른다.</li>
 *     <li>모든 요청에 내부 API 키 헤더({@link InternalApiHeaders#API_KEY}, 값 {@code hapbang.internal-api.key})를 붙인다.</li>
 *     <li>오류 상태는 여기서 바꾼다: 강의 오류 코드가 {@code LECTURE_NOT_FOUND}인 404만 "강의 없음"
 *     ({@link LectureNotFoundResponse})이고, 그 밖의 오류 상태는 모두 {@link LectureCatalogUnavailableException}이다.
 *     본문 없는 404(내부 API를 외부 포트로 부른 설정 오류)와 401(키 오류)을 강의 없음으로 보지 않기 위해서다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class CouponLectureApiConfig {

    static final String LECTURE_NOT_FOUND_CODE = "LECTURE_NOT_FOUND";

    @Bean
    CouponLectureInternalApi couponLectureInternalApi(RestClient.Builder restClientBuilder,
            @Value("${hapbang.coupon.lecture-base-url:http://localhost:${hapbang.internal-api.port:${server.port:8080}}}")
            String baseUrl,
            @Value("${hapbang.internal-api.key}") String internalApiKey) {
        return create(restClientBuilder.clone(), baseUrl, internalApiKey);
    }

    /** 빈과 같은 설정의 프록시. 테스트가 컨텍스트 없이 만들 때도 쓴다(제한 시간은 넘긴 Builder를 따른다) */
    public static CouponLectureInternalApi create(RestClient.Builder restClientBuilder, String baseUrl,
            String internalApiKey) {
        RestClient restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(InternalApiHeaders.API_KEY, internalApiKey)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw statusFailure(response);
                })
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build()
                .createClient(CouponLectureInternalApi.class);
    }

    private static RuntimeException statusFailure(ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        String code = problemCode(response);
        if (status.value() == HttpStatus.NOT_FOUND.value() && LECTURE_NOT_FOUND_CODE.equals(code)) {
            return new LectureNotFoundResponse();
        }
        return new LectureCatalogUnavailableException(
                "강의 조회 내부 API가 " + status.value() + "로 응답했습니다(code=" + code + ").", null);
    }

    /** ProblemDetail의 {@code code}. 본문이 없거나 JSON이 아니면 null */
    private static String problemCode(ClientHttpResponse response) {
        try {
            byte[] body = response.getBody().readAllBytes();
            if (body.length == 0) {
                return null;
            }
            JsonNode code = JsonMapper.shared().readTree(body).get("code");
            return code == null || code.isNull() ? null : code.asString();
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** 강의 앱이 강의가 없다고 답했다(404 LECTURE_NOT_FOUND). 어댑터가 빈 값으로 바꾼다 */
    static final class LectureNotFoundResponse extends RuntimeException {

        LectureNotFoundResponse() {
            super("강의가 없습니다.", null, false, false);
        }
    }
}
