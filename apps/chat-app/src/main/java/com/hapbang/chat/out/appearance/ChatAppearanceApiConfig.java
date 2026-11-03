package com.hapbang.chat.out.appearance;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.hapbang.chat.app.ChatRecruitSourceException;
import com.hapbang.shared.internal.InternalApiHeaders;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 출연 내부 API HTTP Interface({@link ChatAppearanceInternalApi}) 프록시. {@code hapbang.clients.appearance.base-url}이 있을 때만
 * 만든다. 회원 내부 API 프록시({@code ChatMemberApiConfig})와 같은 방식이다.
 * <ul>
 *     <li>모든 요청에 내부 API 키 헤더({@link InternalApiHeaders#API_KEY}, 값 {@code hapbang.internal-api.key})를 붙인다.</li>
 *     <li>오류 상태는 여기서 {@link ChatRecruitSourceException}으로 바꾼다: 404는 모집글 없음(NOT_FOUND), 그 밖의 4xx는
 *     거절(REJECTED), 5xx는 호출 불가(UNAVAILABLE). 연결 실패·시간 초과·읽을 수 없는 성공 응답은 어댑터
 *     ({@code HttpChatRecruitSource})가 바꾼다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hapbang.clients.appearance", name = "base-url")
@EnableConfigurationProperties(ChatAppearanceApiProperties.class)
public class ChatAppearanceApiConfig {

    @Bean
    ChatAppearanceInternalApi chatAppearanceInternalApi(RestClient.Builder restClientBuilder,
            ChatAppearanceApiProperties properties, @Value("${hapbang.internal-api.key}") String internalApiKey) {
        return create(restClientBuilder, properties, internalApiKey);
    }

    /** 빈과 같은 설정의 프록시. 테스트가 컨텍스트 없이 만들 때도 쓴다 */
    public static ChatAppearanceInternalApi create(RestClient.Builder restClientBuilder,
            ChatAppearanceApiProperties properties, String internalApiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient restClient = restClientBuilder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(InternalApiHeaders.API_KEY, internalApiKey)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw statusFailure(response);
                })
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build()
                .createClient(ChatAppearanceInternalApi.class);
    }

    private static ChatRecruitSourceException statusFailure(ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        ChatRecruitSourceException.Reason reason;
        if (status.is5xxServerError()) {
            reason = ChatRecruitSourceException.Reason.UNAVAILABLE;
        } else if (status.value() == HttpStatus.NOT_FOUND.value()) {
            reason = ChatRecruitSourceException.Reason.NOT_FOUND;
        } else {
            reason = ChatRecruitSourceException.Reason.REJECTED;
        }
        return new ChatRecruitSourceException(reason, status.value(), problemCode(response),
                "출연 내부 API가 " + status.value() + "로 응답했습니다.", null);
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
}
