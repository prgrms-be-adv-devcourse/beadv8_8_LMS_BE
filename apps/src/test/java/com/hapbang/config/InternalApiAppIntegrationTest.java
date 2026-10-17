package com.hapbang.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.member.in.MemberOAuth2User;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.PostgresTestcontainersConfig;

import jakarta.servlet.http.Cookie;

// 실제 애플리케이션 전체(모든 SecurityFilterChain·JDBC 세션·springdoc)에서 Internal API 공통 인증(#300 구현, #307 검증)을
// 이미 있는 강의 판매 수익 내부 API(#300)로 확인한다. 테스트 전용 컨트롤러를 두지 않는다.
@SpringBootTest(properties = "hapbang.internal-api.key=" + InternalApiAppIntegrationTest.KEY)
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfig.class)
class InternalApiAppIntegrationTest {

    static final String KEY = "apps-test-internal-key-307";
    private static final String SUMMARY = "/api/v1/internal/lectures/earnings/summary";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    private final List<String> createdSessionIds = new ArrayList<>();

    @AfterEach
    void deleteSessions() {
        createdSessionIds.forEach(sessionRepository::deleteById);
    }

    @Test
    @DisplayName("올바른 키면 강의 내부 API가 응답한다(RsData, asOf는 +09:00). 키가 없거나 틀리면 본문 없는 401")
    void keyRequired() throws Exception {
        mockMvc.perform(get(SUMMARY).param("userId", "12").header(InternalApiHeaders.API_KEY, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.confirmationPending.amountHp").value(0))
                .andExpect(jsonPath("$.data.asOf").value(org.hamcrest.Matchers.endsWith("+09:00")));
        mockMvc.perform(get(SUMMARY).param("userId", "12"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mockMvc.perform(get(SUMMARY).param("userId", "12").header(InternalApiHeaders.API_KEY, "wrong"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mockMvc.perform(get(SUMMARY).param("userId", "12").header(InternalApiHeaders.API_KEY, "  "))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("실제 로그인 세션(JDBC, 관리자·회원)은 회원 API에서는 인증되지만 Internal API 인증 수단이 아니다")
    void realSessionCannotAccessInternal() throws Exception {
        for (Role role : List.of(Role.ADMIN, Role.USER, Role.VERIFIED_INFLUENCER)) {
            Cookie session = loggedInSession(role);

            // 회원 API는 세션으로 인증된다(401이 아니다)
            int publicStatus = mockMvc.perform(get("/api/v1/members/me").cookie(session))
                    .andReturn().getResponse().getStatus();
            assertThat(publicStatus).isNotEqualTo(401);
            mockMvc.perform(get(SUMMARY).param("userId", "77").cookie(session))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("Internal API는 명세(/v3/api-docs)에 나오고(#300 정책) 명세에 키 값은 없다. 명세 노출과 별개로 키 없는 호출은 401이다")
    void internalApiDocumentedButProtected() throws Exception {
        String docs = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/internal/lectures/earnings/summary'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/members/me'].get").exists())
                .andReturn().getResponse().getContentAsString();

        assertThat(docs).doesNotContain(KEY);
        mockMvc.perform(get(SUMMARY).param("userId", "1")).andExpect(status().isUnauthorized());
    }

    // 실제 로그인처럼 시큐리티 컨텍스트를 JDBC 세션에 저장하고, 브라우저가 보내는 SESSION 쿠키(Base64 세션 ID)를 만든다
    private <S extends Session> Cookie loggedInSession(Role role) {
        @SuppressWarnings("unchecked")
        FindByIndexNameSessionRepository<S> repository = (FindByIndexNameSessionRepository<S>) sessionRepository;
        MemberOAuth2User principal = new MemberOAuth2User(77L, role, Map.of());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
        S session = repository.createSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        createdSessionIds.add(session.getId());
        return new Cookie("SESSION",
                Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }
}
