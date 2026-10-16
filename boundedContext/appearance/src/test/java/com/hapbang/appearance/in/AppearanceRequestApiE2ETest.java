package com.hapbang.appearance.in;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.app.AppearanceUserSupport;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.BillingStubs;
import com.hapbang.appearance.support.BillingStubs.Rejection;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.TestLogin;
import com.jayway.jsonpath.JsonPath;

/**
 * 앱 전체(보안 필터, 세션 로그인, CSRF, 메서드 보안, 서비스, DB, Fake Billing)를 띄우고 출연 요청서 API를 호출한다.
 * 로그인은 팀 공통 {@link TestLogin}, 쓰기 요청은 CSRF 토큰을 함께 보낸다.
 */
@AppearanceIntegrationTest
@AutoConfigureMockMvc
class AppearanceRequestApiE2ETest {

    private static final String BASE = "/api/v1/appearances/requests";
    private static final long HOST = 1L;
    private static final long GUEST = 2L;
    private static final long OTHER = 3L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceUserSupport appearanceUserSupport;

    @Autowired
    BillingClient billingClient;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(OTHER));
    }


    private String createBody(long guestId, long fee) {
        LocalDateTime now = LocalDateTime.now(clock);
        return """
                {
                  "guestId": %d,
                  "title": "주말 게임 합방 출연 요청",
                  "hostChannelUrl": "https://www.youtube.com/@host",
                  "scheduledAt": "%s",
                  "expectedMinutes": 60,
                  "content": "같이 신작 게임을 플레이하는 콘텐츠입니다.",
                  "fee": %d,
                  "acceptDeadline": "%s"
                }
                """.formatted(guestId, now.plusDays(7).withNano(0), fee, now.plusDays(2).withNano(0));
    }

    private MockHttpServletRequestBuilder createRequest(long hostId, String body) {
        return post(BASE).with(TestLogin.as(hostId, Role.INFLUENCER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private long sendRequest(long hostId, long guestId) throws Exception {
        String response = mockMvc.perform(createRequest(hostId, createBody(guestId, 30_000)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    @Test
    @DisplayName("요청서 보내기 → 보낸·받은 목록 → 상세 → 수정 흐름 (성공 응답은 RsData: statusCode, message, data)")
    void fullFlow() throws Exception {
        // 보내기: HTTP 201, 본문 statusCode 201, data에 요청서
        String created = mockMvc.perform(createRequest(HOST, createBody(GUEST, 30_000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.message").value("출연 요청을 보냈습니다."))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString();
        long requestId = ((Number) JsonPath.read(created, "$.data.id")).longValue();

        // 보낸 목록 (호스트): data에 PagedModel(content, page)
        mockMvc.perform(get(BASE + "/sent").with(TestLogin.as(HOST, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("보낸 출연 요청 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.content[0].id").value(requestId))
                .andExpect(jsonPath("$.data.content[0].status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.page.totalElements").value(1));

        // 받은 목록 (게스트)
        mockMvc.perform(get(BASE + "/received").with(TestLogin.as(GUEST, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("받은 출연 요청 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.content[0].id").value(requestId))
                .andExpect(jsonPath("$.data.content[0].hostId").value(HOST));

        // 상세 (게스트)
        mockMvc.perform(get(BASE + "/" + requestId).with(TestLogin.as(GUEST, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("출연 요청서를 조회했습니다."))
                .andExpect(jsonPath("$.data.title").value("주말 게임 합방 출연 요청"))
                .andExpect(jsonPath("$.data.fee").value(30_000))
                .andExpect(jsonPath("$.data.expectedMinutes").value(60));

        // 수정 (호스트): 보낸 값만 바뀐다
        mockMvc.perform(patch(BASE + "/" + requestId).with(TestLogin.as(HOST, Role.INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "제목 수정", "hostChannelUrl": "https://chzzk.naver.com/host"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("출연 요청서를 수정했습니다."))
                .andExpect(jsonPath("$.data.title").value("제목 수정"))
                .andExpect(jsonPath("$.data.hostChannelUrl").value("https://chzzk.naver.com/host"))
                .andExpect(jsonPath("$.data.content").value("같이 신작 게임을 플레이하는 콘텐츠입니다."));
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void notLoggedIn() throws Exception {
        mockMvc.perform(get(BASE + "/sent"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("일반 유저·관리자 세션이면 Facade @PreAuthorize에서 403 Problem Detail (공통 FORBIDDEN)")
    void userRole() throws Exception {
        mockMvc.perform(get(BASE + "/sent").with(TestLogin.as(HOST, Role.USER)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post(BASE).with(TestLogin.as(HOST, Role.ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(GUEST, 30_000)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("요청 값이 형식에 맞지 않으면 400 Problem Detail (INVALID_REQUEST)에 항목별 오류를 담는다")
    void invalidBody() throws Exception {
        mockMvc.perform(createRequest(HOST, """
                        {"guestId": 2, "title": " ", "hostChannelUrl": "youtube.com"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("title")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("hostChannelUrl")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("fee")));
    }

    @Test
    @DisplayName("최저 출연료 미달이면 400 (FEE_BELOW_MINIMUM)")
    void feeBelowMinimum() throws Exception {
        mockMvc.perform(createRequest(HOST, createBody(GUEST, 19_999)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.code").value("FEE_BELOW_MINIMUM"))
                .andExpect(jsonPath("$.detail").value("출연료는 최저 출연료(20,000 HP) 이상이어야 합니다."));
    }

    @Test
    @DisplayName("Billing이 잔액 부족으로 보류를 거절하면 그 오류 그대로 409 INSUFFICIENT_BALANCE")
    void insufficientBalance() throws Exception {
        BillingStubs.reject(billingClient, Rejection.INSUFFICIENT_BALANCE);

        mockMvc.perform(createRequest(HOST, createBody(GUEST, 30_000)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.detail").value("HP 잔액이 부족합니다."));
    }

    @Test
    @DisplayName("응답 대기 중인 요청이 있는 게스트에게 다시 보내면 409 (DUPLICATE_APPEARANCE_REQUEST)")
    void duplicate() throws Exception {
        sendRequest(HOST, GUEST);

        mockMvc.perform(createRequest(HOST, createBody(GUEST, 30_000)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_APPEARANCE_REQUEST"));
    }

    @Test
    @DisplayName("Billing이 응답하지 않으면 503 (BILLING_UNAVAILABLE)")
    void billingUnavailable() throws Exception {
        BillingStubs.unavailable(billingClient);

        mockMvc.perform(createRequest(HOST, createBody(GUEST, 30_000)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("BILLING_UNAVAILABLE"));
    }

    @Test
    @DisplayName("다른 회원이 상세를 조회하면 404, 게스트가 수정하면 403 (NOT_REQUEST_HOST)")
    void permission() throws Exception {
        long requestId = sendRequest(HOST, GUEST);

        mockMvc.perform(get(BASE + "/" + requestId).with(TestLogin.as(OTHER, Role.INFLUENCER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPEARANCE_REQUEST_NOT_FOUND"));

        mockMvc.perform(patch(BASE + "/" + requestId).with(TestLogin.as(GUEST, Role.INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"게스트가 바꾼 제목\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_REQUEST_HOST"));
    }

    @Test
    @DisplayName("수정할 값을 하나도 보내지 않으면 400 (INVALID_REQUEST)")
    void updateNothing() throws Exception {
        long requestId = sendRequest(HOST, GUEST);

        mockMvc.perform(patch(BASE + "/" + requestId).with(TestLogin.as(HOST, Role.INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("CSRF 토큰 없이 보내면 403 (보안 필터)")
    void withoutCsrf() throws Exception {
        mockMvc.perform(post(BASE).with(TestLogin.as(HOST, Role.INFLUENCER))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(GUEST, 30_000)))
                .andExpect(status().isForbidden());
    }
}
