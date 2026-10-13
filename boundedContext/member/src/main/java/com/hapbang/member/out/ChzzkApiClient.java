package com.hapbang.member.out;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;

import lombok.extern.slf4j.Slf4j;

/**
 * 치지직 Open API 호출 (채널 인증 전용, 로그인 아님).
 * 응답은 {code, message, content} 형태이고 content만 꺼낸다. 실패하면 CHZZK_API_FAILED(502).
 * 시간 제한은 application.yaml의 spring.http.clients (연결 3초, 읽기 5초). 재시도는 하지 않는다 (code는 1회용)
 */
@Slf4j
@Component
@EnableConfigurationProperties(ChzzkProperties.class)
public class ChzzkApiClient {

    static final String API_BASE_URL = "https://openapi.chzzk.naver.com";
    static final String AUTHORIZE_URL = "https://chzzk.naver.com/account-interlock";
    /** 오류 응답 본문은 이 길이까지만 로그에 남긴다 */
    private static final int LOG_BODY_MAX_LENGTH = 300;

    private final RestClient restClient;
    private final ChzzkProperties properties;

    public ChzzkApiClient(RestClient.Builder restClientBuilder, ChzzkProperties properties) {
        this.restClient = restClientBuilder.baseUrl(API_BASE_URL).build();
        this.properties = properties;
    }

    /** 치지직 계정 연동(로그인) 화면 주소. 끝나면 redirectUri로 code·state가 돌아온다 */
    public String authorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("clientId", properties.clientId())
                .queryParam("redirectUri", properties.redirectUri())
                .queryParam("state", state)
                .encode()
                .toUriString();
    }

    /** code로 Access Token 발급 (유효 1일) */
    public String issueToken(String code, String state) {
        TokenResponse token = call("토큰 발급", () -> restClient.post()
                .uri("/auth/v1/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new TokenRequest("authorization_code", properties.clientId(), properties.clientSecret(),
                        code, state))
                .retrieve()
                .body(new ParameterizedTypeReference<ChzzkResponse<TokenResponse>>() {}));
        return token.accessToken();
    }

    /** Access Token 주인의 채널 */
    public ChzzkUser findMe(String accessToken) {
        return call("내 채널", () -> restClient.get()
                .uri("/open/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(new ParameterizedTypeReference<ChzzkResponse<ChzzkUser>>() {}));
    }

    /** 채널 팔로워 수 (사용자 토큰 없이 Client-Id·Client-Secret으로 조회) */
    public int findFollowerCount(String channelId) {
        ChannelsResponse channels = call("채널 조회", () -> restClient.get()
                .uri(uri -> uri.path("/open/v1/channels").queryParam("channelIds", channelId).build())
                .header("Client-Id", properties.clientId())
                .header("Client-Secret", properties.clientSecret())
                .retrieve()
                .body(new ParameterizedTypeReference<ChzzkResponse<ChannelsResponse>>() {}));
        return channels.data().stream()
                .filter(channel -> channelId.equals(channel.channelId()))
                .findFirst()
                .map(Channel::followerCount)
                .orElseThrow(() -> {
                    log.warn("치지직 채널 조회 결과에 채널 없음: channelId={}", channelId);
                    return new MemberException(MemberErrorCode.CHZZK_API_FAILED);
                });
    }

    /**
     * 채널 확인이 끝난 토큰 폐기 (토큰은 저장하지 않는다).
     * 실패해도 예외를 던지지 않는다: 토큰은 1일 뒤 저절로 만료되므로 채널 등록까지 막을 이유가 없다
     */
    public void revokeToken(String accessToken) {
        try {
            restClient.post()
                    .uri("/auth/v1/token/revoke")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new RevokeRequest(properties.clientId(), properties.clientSecret(), accessToken,
                            "access_token"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("치지직 토큰 폐기 실패 (1일 뒤 자동 만료): {}", e.getMessage());
        }
    }

    /**
     * 호출 실패(연결·4xx·5xx)와 빈 응답을 CHZZK_API_FAILED로 바꾼다. 원인은 로그로 구분한다.
     * 요청 내용(Client Secret·code·토큰)과 성공 응답(토큰)은 로그에 남기지 않는다
     */
    private <T> T call(String api, Supplier<ChzzkResponse<T>> request) {
        ChzzkResponse<T> response;
        try {
            response = request.get();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                // 우리 앱 키(Client ID·Secret) 문제 (치지직 응답 message: INVALID_CLIENT). 사용자가 고칠 수 없으니 바로 확인해야 한다
                log.error("치지직 {} 인증 실패 (Client ID·Secret 확인): body={}", api,
                        abbreviate(e.getResponseBodyAsString()));
            } else {
                // 치지직이 오류로 응답함. 4xx면 우리 요청 문제(code 만료 등), 5xx면 치지직 문제
                log.warn("치지직 {} 오류 응답: status={}, body={}", api, e.getStatusCode(),
                        abbreviate(e.getResponseBodyAsString()));
            }
            throw new MemberException(MemberErrorCode.CHZZK_API_FAILED);
        } catch (RestClientException e) {
            // 응답 자체를 못 받음: 연결 실패, 시간 초과(연결 3초·읽기 5초), 응답 해석 실패
            log.warn("치지직 {} 호출 실패: {}", api, e.getMessage());
            throw new MemberException(MemberErrorCode.CHZZK_API_FAILED);
        }
        if (response == null || response.content() == null) {
            // HTTP는 성공인데 내용이 없음 (치지직 실패 응답 형식 {code, message})
            log.warn("치지직 {} 응답에 content 없음: code={}, message={}", api,
                    response == null ? null : response.code(), response == null ? null : response.message());
            throw new MemberException(MemberErrorCode.CHZZK_API_FAILED);
        }
        return response.content();
    }

    private static String abbreviate(String body) {
        return body.length() <= LOG_BODY_MAX_LENGTH ? body : body.substring(0, LOG_BODY_MAX_LENGTH) + "...";
    }

    record ChzzkResponse<T>(Integer code, String message, T content) {
    }

    record TokenRequest(String grantType, String clientId, String clientSecret, String code, String state) {
    }

    record TokenResponse(String accessToken) {
    }

    record ChannelsResponse(List<Channel> data) {
        ChannelsResponse {
            data = data == null ? List.of() : data;
        }
    }

    record Channel(String channelId, int followerCount) {
    }

    record RevokeRequest(String clientId, String clientSecret, String token, String tokenTypeHint) {
    }
}
