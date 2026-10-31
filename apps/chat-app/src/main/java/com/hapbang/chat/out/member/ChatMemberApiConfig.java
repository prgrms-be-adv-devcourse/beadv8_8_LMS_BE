package com.hapbang.chat.out.member;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.hapbang.chat.app.ChatMemberSourceException;
import com.hapbang.shared.internal.InternalApiHeaders;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 회원 내부 API HTTP Interface({@link ChatMemberInternalApi}) 프록시. {@code hapbang.clients.member.base-url}이 있을 때만 만든다.
 * <ul>
 *     <li>RestClient 위에 {@link HttpServiceProxyFactory}로 만든다. 주소·제한 시간은 {@link ChatMemberApiProperties}의 기존 키를
 *     쓰고, 새 설정 키를 두지 않는다.</li>
 *     <li>모든 요청에 내부 API 키 헤더({@link InternalApiHeaders#API_KEY}, 값 {@code hapbang.internal-api.key})를 붙인다.</li>
 *     <li>오류 상태는 여기서 {@link ChatMemberSourceException}으로 바꾼다: 4xx는 거절(REJECTED), 5xx는 호출 불가(UNAVAILABLE).
 *     둘 다 HTTP 상태와 ProblemDetail의 {@code code}(없으면 null)를 담는다. 연결 실패·시간 초과·읽을 수 없는 성공 응답은
 *     어댑터({@code HttpChatMemberSnapshotSource})가 바꾼다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hapbang.clients.member", name = "base-url")
@EnableConfigurationProperties(ChatMemberApiProperties.class)
public class ChatMemberApiConfig {

    @Bean
    ChatMemberInternalApi chatMemberInternalApi(RestClient.Builder restClientBuilder,
            ChatMemberApiProperties properties, @Value("${hapbang.internal-api.key}") String internalApiKey) {
        return create(restClientBuilder, properties, internalApiKey);
    }

    /** 빈과 같은 설정의 프록시. 테스트가 컨텍스트 없이 만들 때도 쓴다 */
    public static ChatMemberInternalApi create(RestClient.Builder restClientBuilder, ChatMemberApiProperties properties,
            String internalApiKey) {
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
                .createClient(ChatMemberInternalApi.class);
    }

    private static ChatMemberSourceException statusFailure(ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        ChatMemberSourceException.Reason reason = status.is5xxServerError()
                ? ChatMemberSourceException.Reason.UNAVAILABLE
                : ChatMemberSourceException.Reason.REJECTED;
        return new ChatMemberSourceException(reason, status.value(), problemCode(response),
                "회원 내부 API가 " + status.value() + "로 응답했습니다.", null);
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
