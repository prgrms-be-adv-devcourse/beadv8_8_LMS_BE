package com.hapbang.member.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;

/** 실제 치지직은 부르지 않고 MockRestServiceServer로 요청 모양과 응답 해석만 확인한다 */
class ChzzkApiClientTest {

    private static final ChzzkProperties PROPERTIES =
            new ChzzkProperties("client-1", "secret-1", "http://localhost:8080/api/v1/members/channels/chzzk/callback");

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ChzzkApiClient client = new ChzzkApiClient(builder, PROPERTIES);

    @Test
    @DisplayName("연동 화면 주소에 clientId·redirectUri·state를 넣는다")
    void authorizeUrl() {
        assertThat(client.authorizeUrl("state-1")).isEqualTo("https://chzzk.naver.com/account-interlock"
                + "?clientId=client-1"
                + "&redirectUri=http://localhost:8080/api/v1/members/channels/chzzk/callback"
                + "&state=state-1");
    }

    @Test
    @DisplayName("code로 토큰을 받으면 content의 accessToken을 돌려준다")
    void issueToken() {
        server.expect(requestTo("https://openapi.chzzk.naver.com/auth/v1/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"grantType":"authorization_code","clientId":"client-1","clientSecret":"secret-1",
                         "code":"code-1","state":"state-1"}
                        """))
                .andRespond(withSuccess("""
                        {"code":200,"message":null,"content":{"accessToken":"token-1","refreshToken":"refresh-1",
                         "tokenType":"Bearer","expiresIn":"86400"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.issueToken("code-1", "state-1")).isEqualTo("token-1");
        server.verify();
    }

    @Test
    @DisplayName("내 채널은 Bearer 토큰으로 조회한다")
    void findMe() {
        server.expect(requestTo("https://openapi.chzzk.naver.com/open/v1/users/me"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-1"))
                .andRespond(withSuccess("""
                        {"code":200,"message":null,"content":{"channelId":"channel-1","channelName":"방송인"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.findMe("token-1")).isEqualTo(new ChzzkUser("channel-1", "방송인"));
    }

    @Test
    @DisplayName("팔로워 수는 Client-Id·Client-Secret 헤더로 조회한다")
    void findFollowerCount() {
        server.expect(requestTo("https://openapi.chzzk.naver.com/open/v1/channels?channelIds=channel-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Client-Id", "client-1"))
                .andExpect(header("Client-Secret", "secret-1"))
                .andRespond(withSuccess("""
                        {"code":200,"message":null,"content":{"data":[{"channelId":"channel-1","channelName":"방송인",
                         "channelImageUrl":null,"followerCount":12,"verifiedMark":false}]}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.findFollowerCount("channel-1")).isEqualTo(12);
    }

    @Test
    @DisplayName("조회한 채널이 응답에 없으면 CHZZK_API_FAILED")
    void findFollowerCount_notFound() {
        server.expect(requestTo("https://openapi.chzzk.naver.com/open/v1/channels?channelIds=channel-1"))
                .andRespond(withSuccess("""
                        {"code":200,"message":null,"content":{"data":[]}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.findFollowerCount("channel-1"))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.CHZZK_API_FAILED);
    }

    @Test
    @DisplayName("토큰 폐기는 access_token 종류로 요청한다")
    void revokeToken() {
        server.expect(requestTo("https://openapi.chzzk.naver.com/auth/v1/token/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"clientId":"client-1","clientSecret":"secret-1","token":"token-1",
                         "tokenTypeHint":"access_token"}
                        """))
                .andRespond(withSuccess());

        client.revokeToken("token-1");
        server.verify();
    }

    @Test
    @DisplayName("토큰 폐기가 실패해도 예외를 던지지 않는다")
    void revokeToken_failed() {
        server.expect(requestTo("https://openapi.chzzk.naver.com/auth/v1/token/revoke"))
                .andRespond(withServerError());

        assertThatCode(() -> client.revokeToken("token-1")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("치지직이 오류(4xx·5xx)를 주면 CHZZK_API_FAILED")
    void apiError() {
        server.expect(requestTo("https://openapi.chzzk.naver.com/auth/v1/token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo("https://openapi.chzzk.naver.com/open/v1/users/me"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.issueToken("bad-code", "state-1"))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.CHZZK_API_FAILED);
        assertThatThrownBy(() -> client.findMe("token-1"))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.CHZZK_API_FAILED);
    }
}
