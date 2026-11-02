package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.app.AccessTokenIssuer;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

/** JWT 공개 키 목록 (#370): 로그인 없이 받고, 그 키로 발급한 access token을 검증할 수 있다 */
@MemberIntegrationTest
@AutoConfigureMockMvc
class JwksControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RSAKey jwtSigningKey;

    @Autowired
    AccessTokenIssuer accessTokenIssuer;

    @Test
    @DisplayName("로그인 없이 서명 키의 공개 키를 표준 JWKS 형식으로 준다")
    void publicKey() throws Exception {
        mockMvc.perform(get(JwksController.PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").value(jwtSigningKey.getKeyID()))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].n").exists())
                .andExpect(jsonPath("$.keys[0].e").exists());
    }

    @Test
    @DisplayName("개인 키 값(d, p, q 등)은 내보내지 않는다")
    void noPrivateKey() throws Exception {
        mockMvc.perform(get(JwksController.PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andExpect(jsonPath("$.keys[0].p").doesNotExist())
                .andExpect(jsonPath("$.keys[0].q").doesNotExist())
                .andExpect(jsonPath("$.keys[0].dp").doesNotExist())
                .andExpect(jsonPath("$.keys[0].dq").doesNotExist())
                .andExpect(jsonPath("$.keys[0].qi").doesNotExist());
    }

    @Test
    @DisplayName("내부 경로는 내부 API 키를 붙이면 같은 공개 키 목록을 준다 (chat-app의 STOMP 검증용)")
    void internalPublicKey() throws Exception {
        String external = mockMvc.perform(get(JwksController.PATH))
                .andReturn().getResponse().getContentAsString();

        String internal = mockMvc.perform(get(JwksController.INTERNAL_PATH)
                        .header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kid").value(jwtSigningKey.getKeyID()))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(internal).isEqualTo(external);
    }

    @Test
    @DisplayName("내부 경로는 내부 API 키가 없으면 401 (다른 내부 API와 같은 키 인증)")
    void internalPublicKey_withoutKey() throws Exception {
        mockMvc.perform(get(JwksController.INTERNAL_PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("받은 공개 키로 member-app이 발급한 access token을 검증할 수 있다")
    void verifiesIssuedToken() throws Exception {
        String body = mockMvc.perform(get(JwksController.PATH))
                .andReturn().getResponse().getContentAsString();
        RSAKey publicKey = JWKSet.parse(body).getKeyByKeyId(jwtSigningKey.getKeyID()).toRSAKey();

        Jwt jwt = NimbusJwtDecoder.withPublicKey(publicKey.toRSAPublicKey()).build()
                .decode(accessTokenIssuer.issue(7L, Role.VERIFIED_INFLUENCER));

        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(jwt.getClaims().get("role")).isEqualTo("VERIFIED_INFLUENCER");
    }
}
