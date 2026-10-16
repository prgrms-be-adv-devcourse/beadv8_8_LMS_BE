package com.hapbang.appearance.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * 출연 요청 응답 API(수락·거절·응답 전 취소)와 목록 상태 필터를 앱 전체로 호출한다. 성공 응답은 RsData다.
 */
@AppearanceIntegrationTest
@AutoConfigureMockMvc
class AppearanceRequestRespondApiE2ETest {

    private static final String BASE = "/api/v1/appearances/requests";
    private static final long HOST = 1L;
    private static final long GUEST = 2L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    BillingClient billingClient;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
    }


    /** 요청서를 보내고 응답의 {id, version}을 돌려준다 */
    private long[] send() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock);
        String body = """
                {"guestId": %d, "title": "주말 게임 합방 출연 요청", "hostChannelUrl": "https://www.youtube.com/@host",
                 "scheduledAt": "%s", "expectedMinutes": 60, "content": "같이 플레이하는 콘텐츠입니다.",
                 "fee": 30000, "acceptDeadline": "%s"}
                """.formatted(GUEST, now.plusDays(7).withNano(0), now.plusDays(2).withNano(0));
        String response = mockMvc.perform(post(BASE).with(TestLogin.as(HOST, Role.INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(response, "$.data.id")).longValue();
        long version = ((Number) JsonPath.read(response, "$.data.version")).longValue();
        return new long[] {id, version};
    }

    private MockHttpServletRequestBuilder postAs(long userId, String path, String body) {
        return post(BASE + path).with(TestLogin.as(userId, Role.INFLUENCER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    @DisplayName("보내기 응답의 version으로 수락하면 200, ACCEPTED, 성립한 출연 ID")
    void accept() throws Exception {
        long[] sent = send();

        mockMvc.perform(postAs(GUEST, "/" + sent[0] + "/accept", "{\"version\": " + sent[1] + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("출연 요청을 수락했습니다."))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.appearanceId").isNumber())
                .andExpect(jsonPath("$.data.respondedAt").isNotEmpty());
    }

    @Test
    @DisplayName("본 뒤 호스트가 수정했으면 409 APPEARANCE_REQUEST_MODIFIED, version이 없으면 400")
    void acceptModifiedOrMissingVersion() throws Exception {
        long[] sent = send();
        mockMvc.perform(patch(BASE + "/" + sent[0]).with(TestLogin.as(HOST, Role.INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"바뀐 제목\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(postAs(GUEST, "/" + sent[0] + "/accept", "{\"version\": " + sent[1] + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPEARANCE_REQUEST_MODIFIED"));
        mockMvc.perform(postAs(GUEST, "/" + sent[0] + "/accept", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("사유와 함께 거절하면 200, REJECTED, 사유, 반환 완료(DONE)")
    void reject() throws Exception {
        long[] sent = send();

        mockMvc.perform(postAs(GUEST, "/" + sent[0] + "/reject", "{\"reason\": \"다른 방송 일정이 있습니다.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("출연 요청을 거절했습니다."))
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectReason").value("다른 방송 일정이 있습니다."))
                .andExpect(jsonPath("$.data.hpReleaseStatus").value("DONE"));
    }

    @Test
    @DisplayName("반환이 실패해도 거절은 200이고 반환 상태는 PENDING, 사유 501자는 400")
    void rejectReleasePendingOrTooLong() throws Exception {
        long[] sent = send();
        mockMvc.perform(postAs(GUEST, "/" + sent[0] + "/reject", "{\"reason\": \"" + "가".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        BillingStubs.unavailable(billingClient);
        mockMvc.perform(postAs(GUEST, "/" + sent[0] + "/reject", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.hpReleaseStatus").value("PENDING"));
    }

    @Test
    @DisplayName("호스트가 응답 전 취소하면 200, CANCELED, 반환 완료. 게스트가 취소하면 403 NOT_REQUEST_HOST")
    void cancel() throws Exception {
        long[] sent = send();

        mockMvc.perform(post(BASE + "/" + sent[0] + "/cancel").with(TestLogin.as(GUEST, Role.INFLUENCER)).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_REQUEST_HOST"));
        mockMvc.perform(post(BASE + "/" + sent[0] + "/cancel").with(TestLogin.as(HOST, Role.INFLUENCER)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("출연 요청을 취소했습니다."))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.hpReleaseStatus").value("DONE"));
    }

    @Test
    @DisplayName("이미 거절한 요청서를 수락하면 409 APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE")
    void respondAfterRejected() throws Exception {
        long[] sent = send();
        mockMvc.perform(postAs(GUEST, "/" + sent[0] + "/reject", "{}")).andExpect(status().isOk());

        mockMvc.perform(postAs(GUEST, "/" + sent[0] + "/accept", "{\"version\": " + sent[1] + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE"));
    }

    @Test
    @DisplayName("목록 상태 필터: status를 여러 번 보내면 그 상태만, 숨김 상태나 없는 값이면 400")
    void statusFilter() throws Exception {
        long[] rejected = send();
        mockMvc.perform(postAs(GUEST, "/" + rejected[0] + "/reject", "{}")).andExpect(status().isOk());

        mockMvc.perform(get(BASE + "/received?status=REJECTED&status=CANCELED")
                        .with(TestLogin.as(GUEST, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(rejected[0]))
                .andExpect(jsonPath("$.data.page.totalElements").value(1));
        mockMvc.perform(get(BASE + "/received?status=REQUESTED").with(TestLogin.as(GUEST, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.totalElements").value(0));
        mockMvc.perform(get(BASE + "/received?status=HOLD_FAILED").with(TestLogin.as(GUEST, Role.INFLUENCER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get(BASE + "/received?status=UNKNOWN").with(TestLogin.as(GUEST, Role.INFLUENCER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("일반 유저 세션으로 수락하면 403 FORBIDDEN (Facade @PreAuthorize)")
    void userRole() throws Exception {
        long[] sent = send();

        mockMvc.perform(post(BASE + "/" + sent[0] + "/accept").with(TestLogin.as(GUEST, Role.USER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": " + sent[1] + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
