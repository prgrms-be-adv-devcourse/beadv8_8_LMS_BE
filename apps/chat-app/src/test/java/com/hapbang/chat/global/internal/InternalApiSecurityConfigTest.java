package com.hapbang.chat.global.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.chat.global.config.PageConfig;
import com.hapbang.chat.global.config.SecurityConfig;
import com.hapbang.chat.global.response.RsData;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.lecture.domain.LectureEarningStatus;
import com.hapbang.shared.lecture.domain.LectureEarningType;
import com.hapbang.shared.lecture.dto.LectureEarningAmountDto;
import com.hapbang.shared.lecture.dto.LectureEarningItemDto;
import com.hapbang.shared.lecture.dto.LectureEarningPageDto;
import com.hapbang.shared.lecture.dto.LectureEarningSummaryDto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Internal API 보안(#300 구현, #307 보강 검증)을 실제 SecurityConfig(회원 API)·InternalApiSecurityConfig(Internal API) 두 체인으로 검증한다.
// 컨트롤러는 테스트 전용이다(운영 코드에 테스트용 API를 두지 않는다). Internal 응답 계약(RsData·offset 시각·null 생략·페이지 모양)도
// 실제 MVC 변환기로 확인한다.
@ContextConfiguration(classes = com.hapbang.testsupport.WebSliceTestConfiguration.class)
@WebMvcTest(controllers = {InternalApiSecurityConfigTest.InternalProbeController.class,
        InternalApiSecurityConfigTest.PublicProbeController.class}, properties = {
        "spring.config.name=hapbang-module-test",
        "spring.security.oauth2.client.registration.google.client-id=test",
        "spring.security.oauth2.client.registration.google.client-secret=test",
        "hapbang.internal-api.key=" + InternalApiSecurityConfigTest.KEY
})
@Import({SecurityConfig.class, PageConfig.class, InternalApiSecurityConfig.class,
        InternalApiSecurityConfigTest.InternalProbeController.class,
        InternalApiSecurityConfigTest.PublicProbeController.class})
@ExtendWith(OutputCaptureExtension.class)
class InternalApiSecurityConfigTest {

    static final String KEY = "global-test-internal-key-307";
    private static final String PROBE = "/api/v1/internal/probe";
    private static final OffsetDateTime AS_OF = OffsetDateTime.of(2026, 10, 16, 15, 0, 0, 0, ZoneOffset.ofHours(9));

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper jsonMapper;

    // ---------------------------------------------------------------------------------------------
    // 인증
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("올바른 키면 ROLE_INTERNAL 하나로 인증되고, 세션을 만들지 않는다")
    void validKey() throws Exception {
        MvcResult result = mockMvc.perform(get(PROBE).header(InternalApiHeaders.API_KEY, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal").value("internal"))
                .andExpect(jsonPath("$.authorities.length()").value(1))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_INTERNAL"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(KEY);
    }

    @Test
    @DisplayName("키가 없으면 본문 없는 401")
    void missingKey() throws Exception {
        mockMvc.perform(get(PROBE))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-key", "global-test-internal-key-30", "global-test-internal-key-3077",
            "GLOBAL-TEST-INTERNAL-KEY-307", "", " ", "   ", "\t"})
    @DisplayName("키가 다르거나(길이·대소문자 포함) 빈 값·공백이면 같은 본문 없는 401이고 이유를 알리지 않는다")
    void invalidKey(String key) throws Exception {
        mockMvc.perform(get(PROBE).header(InternalApiHeaders.API_KEY, key))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    @DisplayName("앞뒤 공백이 붙은 키는 다른 키다(정확히 같아야 한다)")
    void paddedKey() throws Exception {
        mockMvc.perform(get(PROBE).header(InternalApiHeaders.API_KEY, " " + KEY + " "))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Internal 경로 접두사 자체와 하위 경로 모두 보호한다(없는 경로도 키가 없으면 401, 키가 있으면 404)")
    void wholePrefixProtected() throws Exception {
        mockMvc.perform(get("/api/v1/internal")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/internal/unknown/path")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/internal/unknown/path").header(InternalApiHeaders.API_KEY, KEY))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------------------------------
    // 세션·CSRF·키 노출
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("회원·관리자 로그인 세션만으로는 Internal API에 접근할 수 없다(같은 세션은 회원 API에서는 인증된다)")
    void sessionIsNotInternalAuthentication() throws Exception {
        for (String role : List.of("ROLE_ADMIN", "ROLE_USER", "ROLE_INFLUENCER")) {
            MockHttpSession session = loggedInSession(role);

            mockMvc.perform(get("/api/v1/samples/me").session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authorities[0]").value(role));
            mockMvc.perform(get(PROBE).session(session)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(PROBE).session(session).header(InternalApiHeaders.API_KEY, "wrong-key"))
                    .andExpect(status().isUnauthorized());
            // 세션과 함께 와도 올바른 키면 Internal 인증만 쓴다(세션의 역할이 섞이지 않는다)
            mockMvc.perform(get(PROBE).session(session).header(InternalApiHeaders.API_KEY, KEY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authorities.length()").value(1))
                    .andExpect(jsonPath("$.authorities[0]").value("ROLE_INTERNAL"));
        }
    }

    @Test
    @DisplayName("Internal 키는 회원 API의 인증 수단이 아니다(키를 보내도 회원 API는 로그인이 필요하다)")
    void keyIsNotPublicAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/samples/me").header(InternalApiHeaders.API_KEY, KEY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CSRF: Internal API는 CSRF 토큰 없이 POST할 수 있고, 회원 API는 기존대로 토큰이 없으면 403이다")
    void csrfOnlyDisabledForInternal() throws Exception {
        mockMvc.perform(post(PROBE).header(InternalApiHeaders.API_KEY, KEY)).andExpect(status().isOk());
        mockMvc.perform(post(PROBE)).andExpect(status().isUnauthorized());

        MockHttpSession session = loggedInSession("ROLE_USER");
        mockMvc.perform(post("/api/v1/samples/me").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/samples/me").session(session).with(csrf())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("인증 키는 응답·로그에 남지 않는다(실패 로그에는 경로와 없음·불일치만 남는다)")
    void keyNeverExposed(CapturedOutput output) throws Exception {
        mockMvc.perform(get(PROBE).header(InternalApiHeaders.API_KEY, KEY)).andExpect(status().isOk());
        String rejected = mockMvc.perform(get(PROBE).header(InternalApiHeaders.API_KEY, KEY + "-wrong"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(rejected).doesNotContain(KEY);
        assertThat(output.getAll()).contains("내부 API 인증 실패").doesNotContain(KEY);
    }

    // ---------------------------------------------------------------------------------------------
    // Internal 응답 계약 (#300 Lecture DTO 기준): RsData · offset 시각 · null 생략 · 페이지 모양
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("요약: RsData{statusCode, message, data}, 상태별 금액·건수, asOf는 offset 포함 ISO 8601(+09:00)이고 "
            + "역직렬화한 값과 같은 순간이다")
    void summaryContract() throws Exception {
        String json = mockMvc.perform(get(PROBE + "/summary").header(InternalApiHeaders.API_KEY, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("요약"))
                .andExpect(jsonPath("$.data.confirmationPending.amountHp").value(30_000))
                .andExpect(jsonPath("$.data.confirmationPending.count").value(2))
                .andExpect(jsonPath("$.data.scheduled.amountHp").value(0))
                .andExpect(jsonPath("$.data.failed.count").value(1))
                .andExpect(jsonPath("$.data.asOf").value("2026-10-16T15:00:00+09:00"))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = jsonMapper.readTree(json).get("data");
        LectureEarningSummaryDto read = jsonMapper.treeToValue(data, LectureEarningSummaryDto.class);
        // Jackson이 읽으면서 offset을 바꿀 수 있으므로(예: UTC) 같은 순간인지로 비교한다
        assertThat(read.asOf().toInstant()).isEqualTo(AS_OF.toInstant());
        assertThat(read.failed()).isEqualTo(new LectureEarningAmountDto(5_000L, 1L));
    }

    @Test
    @DisplayName("목록: data = {content, page:{size, number, totalElements, totalPages}}, 정할 수 없는 payoutScheduledAt(null)은 "
            + "필드가 빠지고, 클라이언트 DTO(LectureEarningPageDto)로 다시 읽힌다")
    void pageContract() throws Exception {
        String json = mockMvc.perform(get(PROBE + "/items").header(InternalApiHeaders.API_KEY, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.size").value(20))
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.totalElements").value(21))
                .andExpect(jsonPath("$.data.page.totalPages").value(2))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].sourceType").value("LECTURE"))
                .andExpect(jsonPath("$.data.content[0].sourceId").value(12))
                .andExpect(jsonPath("$.data.content[0].status").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.content[0].payoutScheduledAt").value("2026-11-21T00:00:00+09:00"))
                .andExpect(jsonPath("$.data.content[0].statusUpdatedAt").value("2026-10-16T15:00:00+09:00"))
                .andExpect(jsonPath("$.data.content[1].status").value("ON_HOLD"))
                .andExpect(jsonPath("$.data.content[1].payoutScheduledAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        LectureEarningPageDto page = jsonMapper.treeToValue(jsonMapper.readTree(json).get("data"),
                LectureEarningPageDto.class);
        assertThat(page.page()).isEqualTo(new LectureEarningPageDto.PageMetadata(20, 0, 21, 2));
        assertThat(page.content()).hasSize(2);
        assertThat(page.content().get(0).statusUpdatedAt().toInstant()).isEqualTo(AS_OF.toInstant());
        assertThat(page.content().get(1).payoutScheduledAt()).isNull();
    }

    // 회원 로그인 세션: HttpSessionSecurityContextRepository가 회원 API에서 읽는 형태 그대로 둔다
    private static MockHttpSession loggedInSession(String authority) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated("7", null,
                        List.of(new SimpleGrantedAuthority(authority)))));
        return session;
    }

    record AuthView(Object principal, List<String> authorities) {

        static AuthView current() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            return new AuthView(authentication.getPrincipal(),
                    authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
        }
    }

    @org.springframework.boot.test.context.TestComponent
    @RestController
    static class InternalProbeController {

        @GetMapping(PROBE)
        AuthView get() {
            return AuthView.current();
        }

        @PostMapping(PROBE)
        AuthView post() {
            return AuthView.current();
        }

        @GetMapping(PROBE + "/summary")
        RsData<LectureEarningSummaryDto> summary() {
            return RsData.ok("요약", new LectureEarningSummaryDto(new LectureEarningAmountDto(30_000L, 2L),
                    LectureEarningAmountDto.ZERO, LectureEarningAmountDto.ZERO, LectureEarningAmountDto.ZERO,
                    new LectureEarningAmountDto(5_000L, 1L), AS_OF));
        }

        // 실제 Lecture 내부 API처럼 Page를 그대로 반환한다(global PageConfig가 {content, page} 모양으로 바꾼다)
        @GetMapping(PROBE + "/items")
        RsData<Page<LectureEarningItemDto>> items() {
            List<LectureEarningItemDto> content = List.of(
                    new LectureEarningItemDto(LectureEarningItemDto.SOURCE_TYPE, 12L, LectureEarningType.LECTURE_SALE,
                            "강의", 9_000L, LectureEarningStatus.SCHEDULED,
                            OffsetDateTime.of(2026, 11, 21, 0, 0, 0, 0, ZoneOffset.ofHours(9)), AS_OF),
                    new LectureEarningItemDto(LectureEarningItemDto.SOURCE_TYPE, 3L, LectureEarningType.LECTURE_SALE,
                            "강의", 9_000L, LectureEarningStatus.ON_HOLD, null, AS_OF.minusHours(1)));
            return RsData.ok("목록", new PageImpl<>(content, PageRequest.of(0, 20), 21));
        }
    }

    @org.springframework.boot.test.context.TestComponent
    @RestController
    static class PublicProbeController {

        @GetMapping("/api/v1/samples/me")
        AuthView get() {
            return AuthView.current();
        }

        @PostMapping("/api/v1/samples/me")
        AuthView post() {
            return AuthView.current();
        }
    }
}
