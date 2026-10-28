package com.hapbang.appearance.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hapbang.appearance.app.AppearanceRecruitFacade;
import com.hapbang.appearance.app.AppearanceRequestFacade;
import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.TestLogin;

/**
 * 서비스 테스트에서만 확인하던 출연 오류 코드를 HTTP로 확인한다 (#297): FE가 받는 상태 코드와 {@code code}가 맞는지.
 * 상태는 시계를 실제 순서대로 돌려 만든다 (수락 기한 → 방송 → 확인 기간 끝). 준비는 Facade로 하되, HTTP 요청 뒤에는
 * 세션 인증이 비워지므로 그 뒤의 준비도 HTTP로 한다.
 * {@code CONCURRENT_UPDATE}는 HTTP로 경합을 재현하지 않고 {@code AppearanceConcurrentUpdateTest}(서비스)와
 * 공통 핸들러 테스트로 확인한다.
 */
@AppearanceIntegrationTest
@AutoConfigureMockMvc
@WithMockUser(roles = "INFLUENCER")
class AppearanceErrorCodeApiE2ETest {

    private static final String BASE = "/api/v1/appearances";
    private static final long HOST = 1L;
    private static final long GUEST = 2L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
    }

    private MockHttpServletRequestBuilder postAs(long userId, String path, String body) {
        MockHttpServletRequestBuilder builder = post(path).with(TestLogin.as(userId, Role.INFLUENCER)).with(csrf());
        return body == null ? builder : builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void expectError(MockHttpServletRequestBuilder request, HttpStatus status, String code) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().is(status.value()))
                .andExpect(jsonPath("$.status").value(status.value()))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    private AppearanceRequestResDto sendRequest() {
        return appearanceRequestFacade.create(HOST, GUEST, AppearanceRequestFixture.content(clock.now()));
    }

    private long establish() {
        AppearanceRequestResDto sent = sendRequest();
        return appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()).appearanceId();
    }

    private LocalDateTime scheduledAtOf(long appearanceId) {
        return jdbcTemplate.queryForObject("select scheduled_at from appearances.appearances where id = ?",
                LocalDateTime.class, appearanceId);
    }

    private static String requestBody(long guestId, LocalDateTime scheduledAt, LocalDateTime acceptDeadline) {
        return """
                {"guestId": %d, "title": "주말 게임 합방 출연 요청", "hostChannelUrl": "https://www.youtube.com/@host",
                 "scheduledAt": "%s", "expectedMinutes": 60, "content": "같이 플레이합니다.", "fee": 30000,
                 "acceptDeadline": "%s"}
                """.formatted(guestId, scheduledAt.withNano(0), acceptDeadline.withNano(0));
    }

    private static String recruitBody(LocalDateTime scheduledAt, LocalDateTime recruitDeadline) {
        return """
                {"title": "주말 게임 합방 게스트 모집", "hostChannelUrl": "https://www.youtube.com/@host",
                 "scheduledAt": "%s", "expectedMinutes": 60, "content": "같이 플레이할 게스트를 모집합니다.",
                 "feePerPerson": 30000, "recruitCount": 3, "recruitDeadline": "%s"}
                """.formatted(scheduledAt.withNano(0), recruitDeadline.withNano(0));
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("출연 요청")
    class Request {

        @Test
        @DisplayName("나에게 보내기 400 CANNOT_REQUEST_SELF, 수락 기한이 방송 예정 일시 이후 400 INVALID_REQUEST_SCHEDULE")
        void create() throws Exception {
            LocalDateTime now = clock.now();
            expectError(postAs(HOST, BASE + "/requests", requestBody(HOST, now.plusDays(7), now.plusDays(2))),
                    HttpStatus.BAD_REQUEST, "CANNOT_REQUEST_SELF");
            expectError(postAs(HOST, BASE + "/requests", requestBody(GUEST, now.plusDays(2), now.plusDays(3))),
                    HttpStatus.BAD_REQUEST, "INVALID_REQUEST_SCHEDULE");
        }

        @Test
        @DisplayName("호스트가 자기 요청 수락 403 NOT_REQUEST_GUEST, 응답이 끝난 요청서 수정 409 APPEARANCE_REQUEST_NOT_EDITABLE")
        void respondAndEdit() throws Exception {
            AppearanceRequestResDto sent = sendRequest();
            String path = BASE + "/requests/" + sent.id();

            expectError(postAs(HOST, path + "/accept", "{\"version\": %d}".formatted(sent.version())),
                    HttpStatus.FORBIDDEN, "NOT_REQUEST_GUEST");
            mockMvc.perform(postAs(GUEST, path + "/reject", "{}")).andExpect(status().isOk());
            expectError(patch(path).with(TestLogin.as(HOST, Role.INFLUENCER)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"바꾼 제목\"}"),
                    HttpStatus.CONFLICT, "APPEARANCE_REQUEST_NOT_EDITABLE");
        }

        @Test
        @DisplayName("수락 기한이 지나면(만료 배치 전이어도) 수락 409 APPEARANCE_REQUEST_EXPIRED")
        void expired() throws Exception {
            AppearanceRequestResDto sent = sendRequest();
            clock.advanceTo(sent.acceptDeadline());

            expectError(postAs(GUEST, BASE + "/requests/" + sent.id() + "/accept",
                    "{\"version\": %d}".formatted(sent.version())), HttpStatus.CONFLICT, "APPEARANCE_REQUEST_EXPIRED");
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("출연 모집")
    class Recruit {

        @Test
        @DisplayName("모집 마감 일시가 방송 예정 일시 이후 400 INVALID_RECRUIT_SCHEDULE, 마감된 모집글 수정 409 APPEARANCE_RECRUIT_NOT_EDITABLE")
        void createAndEdit() throws Exception {
            LocalDateTime now = clock.now();
            Long recruitId = appearanceRecruitFacade.create(HOST, AppearanceRecruitFixture.content(now)).id();
            expectError(postAs(HOST, BASE + "/recruits", recruitBody(now.plusDays(2), now.plusDays(3))),
                    HttpStatus.BAD_REQUEST, "INVALID_RECRUIT_SCHEDULE");

            mockMvc.perform(postAs(HOST, BASE + "/recruits/" + recruitId + "/close", null)).andExpect(status().isOk());
            expectError(patch(BASE + "/recruits/" + recruitId).with(TestLogin.as(HOST, Role.INFLUENCER)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"바꾼 제목\"}"),
                    HttpStatus.CONFLICT, "APPEARANCE_RECRUIT_NOT_EDITABLE");
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("출연 취소 요청·확인")
    class Appearance {

        @Test
        @DisplayName("호스트가 취소 요청 403 NOT_APPEARANCE_GUEST, 처리 중인 요청 없이 응답 409 CANCEL_REQUEST_NOT_PENDING, "
                + "거절 두 번 뒤 세 번째 요청 409 CANCEL_REQUEST_LIMIT_EXCEEDED")
        void cancelRequest() throws Exception {
            long appearanceId = establish();
            String path = BASE + "/" + appearanceId + "/cancel-requests";

            expectError(postAs(HOST, path, "{\"reason\": \"사유\"}"), HttpStatus.FORBIDDEN, "NOT_APPEARANCE_GUEST");
            expectError(postAs(HOST, path + "/accept", null), HttpStatus.CONFLICT, "CANCEL_REQUEST_NOT_PENDING");
            for (int round = 0; round < 2; round++) {
                mockMvc.perform(postAs(GUEST, path, "{\"reason\": \"일정이 생겼습니다.\"}")).andExpect(status().isOk());
                mockMvc.perform(postAs(HOST, path + "/reject", null)).andExpect(status().isOk());
            }
            expectError(postAs(GUEST, path, "{\"reason\": \"세 번째\"}"), HttpStatus.CONFLICT,
                    "CANCEL_REQUEST_LIMIT_EXCEEDED");
        }

        @Test
        @DisplayName("방송 예정 일시가 지나면 취소 요청·일방 취소 409 APPEARANCE_SCHEDULE_PASSED, "
                + "확인 기간(7일)이 끝나면 확인·불참 409 APPEARANCE_CONFIRM_PERIOD_ENDED")
        void afterSchedule() throws Exception {
            long appearanceId = establish();
            String path = BASE + "/" + appearanceId;

            clock.advanceTo(scheduledAtOf(appearanceId));
            expectError(postAs(GUEST, path + "/cancel-requests", "{\"reason\": \"사유\"}"), HttpStatus.CONFLICT,
                    "APPEARANCE_SCHEDULE_PASSED");
            expectError(postAs(HOST, path + "/host-cancel", null), HttpStatus.CONFLICT, "APPEARANCE_SCHEDULE_PASSED");

            clock.advanceTo(scheduledAtOf(appearanceId).plusDays(7));
            expectError(postAs(HOST, path + "/confirm", null), HttpStatus.CONFLICT, "APPEARANCE_CONFIRM_PERIOD_ENDED");
            expectError(postAs(HOST, path + "/absent", null), HttpStatus.CONFLICT, "APPEARANCE_CONFIRM_PERIOD_ENDED");
        }
    }
}
