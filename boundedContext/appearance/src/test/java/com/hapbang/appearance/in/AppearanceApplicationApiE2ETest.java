package com.hapbang.appearance.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.TestLogin;
import com.jayway.jsonpath.JsonPath;

/**
 * 앱 전체(보안 필터, 세션 로그인, CSRF, 메서드 보안, 서비스, DB, Billing 대역)를 띄우고 출연 모집 지원 API를 호출한다.
 * 성공 응답은 RsData다.
 */
@AppearanceIntegrationTest
@AutoConfigureMockMvc
class AppearanceApplicationApiE2ETest {

    private static final String RECRUITS = "/api/v1/appearances/recruits";
    private static final String APPLICATIONS = "/api/v1/appearances/applications";
    private static final long HOST = 1L;
    private static final long GUEST = 2L;
    private static final long GUEST2 = 3L;
    private static final long VIEWER = 4L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST2));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.normalUser(VIEWER));
    }


    private MockHttpServletRequestBuilder postAs(long userId, String path, String body) {
        return post(path).with(TestLogin.as(userId, Role.INFLUENCER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 호스트가 정원 {@code recruitCount}명 모집글을 올리고 ID를 돌려준다 */
    private long postRecruit(int recruitCount) throws Exception {
        LocalDateTime now = LocalDateTime.now(clock);
        String body = """
                {"title": "주말 게임 합방 게스트 모집", "hostChannelUrl": "https://www.youtube.com/@host",
                 "scheduledAt": "%s", "expectedMinutes": 60, "content": "같이 플레이할 게스트를 모집합니다.",
                 "feePerPerson": 30000, "recruitCount": %d, "recruitDeadline": "%s"}
                """.formatted(now.plusDays(7).withNano(0), recruitCount, now.plusDays(2).withNano(0));
        String response = mockMvc.perform(postAs(HOST, RECRUITS, body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    /** 지원하고 지원 ID를 돌려준다 */
    private long apply(long guestId, long recruitId) throws Exception {
        String response = mockMvc.perform(postAs(guestId, RECRUITS + "/" + recruitId + "/applications",
                        "{\"message\": \"지원합니다.\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    @Test
    @DisplayName("지원하면 201, RsData, APPLIED, 메시지. 다시 지원하면 409 DUPLICATE_APPLICATION")
    void apply() throws Exception {
        long recruitId = postRecruit(3);

        mockMvc.perform(postAs(GUEST, RECRUITS + "/" + recruitId + "/applications", "{\"message\": \"지원합니다.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.message").value("출연 모집에 지원했습니다."))
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.recruitId").value(recruitId))
                .andExpect(jsonPath("$.data.message").value("지원합니다."));
        mockMvc.perform(postAs(GUEST, RECRUITS + "/" + recruitId + "/applications", "{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_APPLICATION"));
    }

    @Test
    @DisplayName("메시지는 선택이라 요청 본문 없이 지원해도 201, 메시지 null")
    void applyWithoutBody() throws Exception {
        long recruitId = postRecruit(3);

        mockMvc.perform(post(RECRUITS + "/" + recruitId + "/applications")
                        .with(TestLogin.as(GUEST, Role.INFLUENCER)).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.message").doesNotExist());
    }

    @Test
    @DisplayName("메시지 501자 400, 자기 모집글 400 CANNOT_APPLY_OWN_RECRUIT, 일반 유저 403 FORBIDDEN")
    void applyRejected() throws Exception {
        long recruitId = postRecruit(3);

        mockMvc.perform(postAs(GUEST, RECRUITS + "/" + recruitId + "/applications",
                        "{\"message\": \"" + "가".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(postAs(HOST, RECRUITS + "/" + recruitId + "/applications", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_APPLY_OWN_RECRUIT"));
        mockMvc.perform(post(RECRUITS + "/" + recruitId + "/applications").with(TestLogin.as(VIEWER, Role.USER))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("수락하면 200, SELECTED, 출연 ID. 정원이 찼으면 다음 수락은 409 RECRUIT_FULL, 지원은 계속 201")
    void acceptAndFull() throws Exception {
        long recruitId = postRecruit(1);
        long first = apply(GUEST, recruitId);
        long second = apply(GUEST2, recruitId);

        mockMvc.perform(postAs(HOST, APPLICATIONS + "/" + first + "/accept", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("지원을 수락했습니다."))
                .andExpect(jsonPath("$.data.status").value("SELECTED"))
                .andExpect(jsonPath("$.data.appearanceId").isNumber());
        mockMvc.perform(postAs(HOST, APPLICATIONS + "/" + second + "/accept", ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECRUIT_FULL"));
        mockMvc.perform(get(RECRUITS + "/" + recruitId).with(TestLogin.as(VIEWER, Role.USER)))
                .andExpect(jsonPath("$.data.status").value("RECRUITING"))
                .andExpect(jsonPath("$.data.selectedCount").value(1))
                .andExpect(jsonPath("$.data.applicantCount").value(2));
    }

    @Test
    @DisplayName("거절하면 NOT_SELECTED, 다른 회원의 수락은 403 NOT_RECRUIT_HOST")
    void rejectAndNotHost() throws Exception {
        long recruitId = postRecruit(3);
        long applicationId = apply(GUEST, recruitId);

        mockMvc.perform(postAs(GUEST2, APPLICATIONS + "/" + applicationId + "/accept", ""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_RECRUIT_HOST"));
        mockMvc.perform(postAs(HOST, APPLICATIONS + "/" + applicationId + "/reject", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("지원을 거절했습니다."))
                .andExpect(jsonPath("$.data.status").value("NOT_SELECTED"));
    }

    @Test
    @DisplayName("지원 취소하면 WITHDRAWN, 다른 회원은 403 NOT_APPLICATION_GUEST, 두 번째 취소는 409")
    void withdraw() throws Exception {
        long recruitId = postRecruit(3);
        long applicationId = apply(GUEST, recruitId);

        mockMvc.perform(postAs(GUEST2, APPLICATIONS + "/" + applicationId + "/withdraw", ""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_APPLICATION_GUEST"));
        mockMvc.perform(postAs(GUEST, APPLICATIONS + "/" + applicationId + "/withdraw", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("지원을 취소했습니다."))
                .andExpect(jsonPath("$.data.status").value("WITHDRAWN"));
        mockMvc.perform(postAs(GUEST, APPLICATIONS + "/" + applicationId + "/withdraw", ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_PENDING"));
    }

    @Test
    @DisplayName("지원자 목록은 호스트만(상태 필터), 내 지원 목록은 내 것만, 없는 상태 값은 400")
    void lists() throws Exception {
        long recruitId = postRecruit(3);
        long mine = apply(GUEST, recruitId);
        apply(GUEST2, recruitId);

        mockMvc.perform(get(RECRUITS + "/" + recruitId + "/applications?status=APPLIED")
                        .with(TestLogin.as(HOST, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("지원자 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.page.totalElements").value(2));
        mockMvc.perform(get(RECRUITS + "/" + recruitId + "/applications").with(TestLogin.as(GUEST, Role.INFLUENCER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_RECRUIT_HOST"));
        mockMvc.perform(get(APPLICATIONS + "/mine").with(TestLogin.as(GUEST, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("내 지원 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.content[0].id").value(mine))
                .andExpect(jsonPath("$.data.content[0].recruitTitle").value("주말 게임 합방 게스트 모집"))
                .andExpect(jsonPath("$.data.content[0].recruitScheduledAt").isNotEmpty())
                .andExpect(jsonPath("$.data.content[0].recruitStatus").value("RECRUITING"))
                .andExpect(jsonPath("$.data.page.totalElements").value(1));
        mockMvc.perform(get(APPLICATIONS + "/mine?status=UNKNOWN").with(TestLogin.as(GUEST, Role.INFLUENCER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("모집글 상세의 myApplication: 지원한 게스트는 내 지원, 지원 안 한 회원·호스트는 null (#243)")
    void myApplicationInDetail() throws Exception {
        long recruitId = postRecruit(3);
        long mine = apply(GUEST, recruitId);

        mockMvc.perform(get(RECRUITS + "/" + recruitId).with(TestLogin.as(GUEST, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myApplication.id").value(mine))
                .andExpect(jsonPath("$.data.myApplication.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.myApplication.message").value("지원합니다."))
                .andExpect(jsonPath("$.data.myApplication.appearanceId").doesNotExist());
        mockMvc.perform(get(RECRUITS + "/" + recruitId).with(TestLogin.as(VIEWER, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myApplication").doesNotExist());
        mockMvc.perform(get(RECRUITS + "/" + recruitId).with(TestLogin.as(HOST, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myApplication").doesNotExist());
    }

    @Test
    @DisplayName("일반 유저가 보는 공개 목록에도 지원자 수가 담긴다")
    void applicantCountInOpenList() throws Exception {
        long recruitId = postRecruit(3);
        apply(GUEST, recruitId);

        mockMvc.perform(get(RECRUITS).with(TestLogin.as(VIEWER, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].applicantCount").value(1));
    }

    @Test
    @DisplayName("없는 지원 수락은 404 APPLICATION_NOT_FOUND")
    void notFound() throws Exception {
        mockMvc.perform(postAs(HOST, APPLICATIONS + "/999999/accept", ""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));
    }
}
