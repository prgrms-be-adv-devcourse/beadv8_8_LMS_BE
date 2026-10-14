package com.hapbang.appearance.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
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
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.billing.FakeBillingClient;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;
import com.jayway.jsonpath.JsonPath;

/**
 * 앱 전체(보안 필터, 세션 로그인, CSRF, 메서드 보안, 서비스, DB, Fake Billing)를 띄우고 출연 모집글 API를 호출한다.
 * 성공 응답은 RsData다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class AppearanceRecruitApiE2ETest {

    private static final String BASE = "/api/v1/appearances/recruits";
    private static final long HOST = 1L;
    private static final long OTHER = 3L;
    private static final long VIEWER = 4L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    FakeBillingClient fakeBillingClient;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(OTHER));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.normalUser(VIEWER));
    }

    @AfterEach
    void tearDown() {
        fakeBillingClient.reset();
    }

    private String createBody(long feePerPerson, int recruitCount) {
        LocalDateTime now = LocalDateTime.now(clock);
        return """
                {"title": "주말 게임 합방 게스트 모집", "hostChannelUrl": "https://www.youtube.com/@host",
                 "scheduledAt": "%s", "expectedMinutes": 60, "content": "같이 플레이할 게스트를 모집합니다.",
                 "feePerPerson": %d, "recruitCount": %d, "recruitDeadline": "%s"}
                """.formatted(now.plusDays(7).withNano(0), feePerPerson, recruitCount, now.plusDays(2).withNano(0));
    }

    private MockHttpServletRequestBuilder createRequest(long hostId, Role role, String body) {
        return post(BASE).with(TestLogin.as(hostId, role)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 모집글을 올리고 ID를 돌려준다 */
    private long postRecruit(long hostId) throws Exception {
        String response = mockMvc.perform(createRequest(hostId, Role.INFLUENCER, createBody(30_000, 3)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    @Test
    @DisplayName("올리면 201, RsData, RECRUITING, 1인당 출연료·모집 인원·성립 인원 0")
    void create() throws Exception {
        mockMvc.perform(createRequest(HOST, Role.INFLUENCER, createBody(30_000, 3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.message").value("출연 모집글을 올렸습니다."))
                .andExpect(jsonPath("$.data.status").value("RECRUITING"))
                .andExpect(jsonPath("$.data.feePerPerson").value(30_000))
                .andExpect(jsonPath("$.data.recruitCount").value(3))
                .andExpect(jsonPath("$.data.selectedCount").value(0))
                .andExpect(jsonPath("$.data.version").isNumber());
    }

    @Test
    @DisplayName("모집 인원 11명은 400 RECRUIT_COUNT_OUT_OF_RANGE, 모집 인원 누락은 400 INVALID_REQUEST")
    void createInvalid() throws Exception {
        mockMvc.perform(createRequest(HOST, Role.INFLUENCER, createBody(30_000, 11)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECRUIT_COUNT_OUT_OF_RANGE"))
                .andExpect(jsonPath("$.detail").value("모집 인원은 1명 이상 10명 이하여야 합니다."));
        mockMvc.perform(createRequest(HOST, Role.INFLUENCER, createBody(30_000, 3).replace("\"recruitCount\": 3,", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("잔액이 부족하면 Billing 오류 그대로 409 INSUFFICIENT_BALANCE")
    void insufficientBalance() throws Exception {
        fakeBillingClient.setBalance(HOST, 89_999);

        mockMvc.perform(createRequest(HOST, Role.INFLUENCER, createBody(30_000, 3)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    @DisplayName("일반 유저는 공개 목록·상세를 볼 수 있지만 올릴 수는 없다 (403 FORBIDDEN)")
    void normalUser() throws Exception {
        long recruitId = postRecruit(HOST);

        mockMvc.perform(get(BASE).with(TestLogin.as(VIEWER, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("모집 중인 출연 모집글 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.content[0].id").value(recruitId))
                .andExpect(jsonPath("$.data.page.totalElements").value(1));
        mockMvc.perform(get(BASE + "/" + recruitId).with(TestLogin.as(VIEWER, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("출연 모집글을 조회했습니다."))
                .andExpect(jsonPath("$.data.content").value("같이 플레이할 게스트를 모집합니다."));
        mockMvc.perform(createRequest(VIEWER, Role.USER, createBody(30_000, 3)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("로그인하지 않으면 공개 목록도 401 (비회원 공개는 팀 합의 후)")
    void anonymous() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("내 목록은 내가 올린 모집글만, 숨김 상태 필터는 400")
    void mine() throws Exception {
        long mine = postRecruit(HOST);
        postRecruit(OTHER);

        mockMvc.perform(get(BASE + "/mine?status=RECRUITING").with(TestLogin.as(HOST, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("내 출연 모집글 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.content[0].id").value(mine))
                .andExpect(jsonPath("$.data.page.totalElements").value(1));
        mockMvc.perform(get(BASE + "/mine?status=HOLD_PENDING").with(TestLogin.as(HOST, Role.INFLUENCER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("호스트는 기본 정보를 수정하고, 다른 회원은 403 NOT_RECRUIT_HOST, 빈 수정은 400")
    void update() throws Exception {
        long recruitId = postRecruit(HOST);

        mockMvc.perform(patch(BASE + "/" + recruitId).with(TestLogin.as(HOST, Role.INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"바뀐 제목\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("출연 모집글을 수정했습니다."))
                .andExpect(jsonPath("$.data.title").value("바뀐 제목"));
        mockMvc.perform(patch(BASE + "/" + recruitId).with(TestLogin.as(OTHER, Role.INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"남의 글\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_RECRUIT_HOST"));
        mockMvc.perform(patch(BASE + "/" + recruitId).with(TestLogin.as(HOST, Role.INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("없는 모집글 상세는 404 APPEARANCE_RECRUIT_NOT_FOUND")
    void notFound() throws Exception {
        mockMvc.perform(get(BASE + "/999999").with(TestLogin.as(VIEWER, Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPEARANCE_RECRUIT_NOT_FOUND"));
    }
}
