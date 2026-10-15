package com.hapbang.appearance.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hapbang.appearance.app.AppearanceRequestFacade;
import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.billing.FakeBillingClient;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

/**
 * 앱 전체(보안 필터, 세션 로그인, CSRF, 메서드 보안, DB, Fake Billing)를 띄우고 출연 조회·취소 요청 API를 호출한다.
 * 출연 성립까지는 Facade로 준비한다 (세션 역할은 {@code @WithMockUser}, API 호출은 {@link TestLogin}).
 */
@IntegrationTest
@AutoConfigureMockMvc
@WithMockUser(roles = "INFLUENCER")
class AppearanceApiCancelE2ETest {

    private static final String BASE = "/api/v1/appearances";
    private static final long HOST = 1L;
    private static final long GUEST = 2L;
    private static final long OTHER = 3L;
    private static final long VIEWER = 4L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    FakeBillingClient fakeBillingClient;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(OTHER));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.normalUser(VIEWER));
    }

    @AfterEach
    void tearDown() {
        fakeBillingClient.reset();
    }

    /** 요청서가 수락돼 성립한 출연 ID */
    private long establish() {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                AppearanceRequestFixture.content(LocalDateTime.now(clock)));
        return appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()).appearanceId();
    }

    private MockHttpServletRequestBuilder postAs(long userId, String path, String body) {
        MockHttpServletRequestBuilder builder = post(path).with(TestLogin.as(userId, Role.INFLUENCER)).with(csrf());
        if (body == null) {
            return builder;
        }
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    @DisplayName("내 출연 목록(role 필수)·상세: 200 RsData, 다른 회원 상세 404, 일반 유저 403")
    void query() throws Exception {
        long appearanceId = establish();

        mockMvc.perform(get(BASE + "?role=GUEST").with(TestLogin.as(GUEST, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("내 출연 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.content[0].id").value(appearanceId))
                .andExpect(jsonPath("$.data.content[0].title").isNotEmpty())
                .andExpect(jsonPath("$.data.page.totalElements").value(1));
        mockMvc.perform(get(BASE).with(TestLogin.as(GUEST, Role.INFLUENCER)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE + "/" + appearanceId).with(TestLogin.as(HOST, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("출연을 조회했습니다."))
                .andExpect(jsonPath("$.data.status").value("ESTABLISHED"))
                .andExpect(jsonPath("$.data.cancelRequests").isEmpty());
        mockMvc.perform(get(BASE + "/" + appearanceId).with(TestLogin.as(OTHER, Role.INFLUENCER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPEARANCE_NOT_FOUND"));
        mockMvc.perform(get(BASE + "/" + appearanceId).with(TestLogin.as(VIEWER, Role.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("취소 요청 200 CANCEL_REQUESTED → 다시 요청 409 → 게스트가 수락 403 → 호스트 거절(본문 없음) 200 → 2차 요청 → 수락 200 CANCELED_BY_AGREEMENT")
    void cancelFlow() throws Exception {
        long appearanceId = establish();
        String cancelPath = BASE + "/" + appearanceId + "/cancel-requests";

        mockMvc.perform(postAs(GUEST, cancelPath, "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(postAs(GUEST, cancelPath, "{\"reason\": \"일정이 생겼습니다.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("출연 취소를 요청했습니다."))
                .andExpect(jsonPath("$.data.status").value("CANCEL_REQUESTED"))
                .andExpect(jsonPath("$.data.cancelRequests[0].reason").value("일정이 생겼습니다."));
        mockMvc.perform(postAs(GUEST, cancelPath, "{\"reason\": \"다시\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCEL_REQUEST_ALREADY_PENDING"));
        mockMvc.perform(postAs(GUEST, cancelPath + "/accept", null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_APPEARANCE_HOST"));
        mockMvc.perform(postAs(HOST, cancelPath + "/reject", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("출연 취소 요청을 거절했습니다."))
                .andExpect(jsonPath("$.data.status").value("ESTABLISHED"))
                .andExpect(jsonPath("$.data.cancelRequests[0].status").value("REJECTED"));

        mockMvc.perform(postAs(GUEST, cancelPath, "{\"reason\": \"정말 어렵습니다.\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(postAs(HOST, cancelPath + "/accept", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("출연 취소 요청을 수락했습니다."))
                .andExpect(jsonPath("$.data.status").value("CANCELED_BY_AGREEMENT"))
                .andExpect(jsonPath("$.data.cancelRequests[1].sequence").value(2))
                .andExpect(jsonPath("$.data.cancelRequests[1].status").value("ACCEPTED"));
        mockMvc.perform(postAs(GUEST, cancelPath, "{\"reason\": \"세 번째\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPEARANCE_NOT_CANCELABLE"));
    }

    @Test
    @DisplayName("CSRF 토큰이 없으면 403, 숫자가 아닌 출연 ID는 출연 API로 매핑되지 않는다")
    void csrfAndPath() throws Exception {
        long appearanceId = establish();

        mockMvc.perform(post(BASE + "/" + appearanceId + "/cancel-requests").with(TestLogin.as(GUEST, Role.INFLUENCER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"사유\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/abc").with(TestLogin.as(GUEST, Role.INFLUENCER)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("호스트 일방 취소: 처리 중인 취소 요청이 있으면 확인 없이 409 CANCEL_REQUEST_CONFIRM_REQUIRED, 확인하면 200 CANCELED_BY_HOST·위약금 9,000·정산 DONE. 게스트는 403")
    void hostCancel() throws Exception {
        long appearanceId = establish();
        String path = BASE + "/" + appearanceId + "/host-cancel";
        mockMvc.perform(postAs(GUEST, BASE + "/" + appearanceId + "/cancel-requests", "{\"reason\": \"사유\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(postAs(GUEST, path, null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_APPEARANCE_HOST"));
        mockMvc.perform(postAs(HOST, path, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCEL_REQUEST_CONFIRM_REQUIRED"));
        mockMvc.perform(postAs(HOST, path, "{\"reason\": \"방송 취소\", \"confirmPendingCancelRequest\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("출연을 취소했습니다."))
                .andExpect(jsonPath("$.data.status").value("CANCELED_BY_HOST"))
                .andExpect(jsonPath("$.data.penaltyAmount").value(9_000))
                .andExpect(jsonPath("$.data.hostCancelReason").value("방송 취소"))
                .andExpect(jsonPath("$.data.hpPenaltyStatus").value("DONE"))
                .andExpect(jsonPath("$.data.cancelRequests[0].status").value("CLOSED_BY_HOST_CANCEL"));
    }
}
