package com.hapbang.appearance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.app.AppearanceUserService;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.global.auth.LoginUserId;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

import lombok.RequiredArgsConstructor;

/**
 * 앱 전체(보안 필터, 세션 로그인, 출연 서비스, DB)를 띄우고 API 요청 흐름을 확인한다.
 * <ul>
 *     <li>세션 로그인 → {@code @LoginUserId}로 회원 ID 주입</li>
 *     <li>출연 회원 자격 확인 → 실패하면 Problem Detail 응답</li>
 * </ul>
 * 아직 출연 API가 없어서 테스트 전용 컨트롤러로 확인한다. 출연 API가 생기면 실제 API로 흐름을 검증한다.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(AppearanceApiE2ETest.ParticipantProbeController.class)
class AppearanceApiE2ETest {

    private static final String PROBE_PATH = "/test/appearance/participant";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AppearanceUserService appearanceUserService;

    @Test
    @DisplayName("로그인하지 않으면 401")
    void notLoggedIn() throws Exception {
        mockMvc.perform(get(PROBE_PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그인한 인플루언서는 세션의 회원 ID로 출연 회원 자격이 확인된다")
    void influencer() throws Exception {
        appearanceUserService.sync(AppearanceUserFixture.influencer(1L));

        mockMvc.perform(get(PROBE_PATH).with(TestLogin.as(1L, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(content().string("1"));
    }

    @Test
    @DisplayName("출연 회원 정보가 없으면 404 Problem Detail (APPEARANCE_USER_NOT_FOUND)")
    void appearanceUserNotFound() throws Exception {
        mockMvc.perform(get(PROBE_PATH).with(TestLogin.as(2L, Role.INFLUENCER)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("APPEARANCE_USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("일반 유저는 403 Problem Detail (NOT_INFLUENCER), 한글 메시지가 UTF-8로 나간다")
    void normalUser() throws Exception {
        appearanceUserService.sync(AppearanceUserFixture.normalUser(3L));

        mockMvc.perform(get(PROBE_PATH).with(TestLogin.as(3L, Role.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_INFLUENCER"))
                .andExpect(jsonPath("$.detail").value("인플루언서 이상만 출연 거래에 참여할 수 있습니다."));
    }

    /**
     * 테스트 전용 컨트롤러. {@code @TestComponent}라서 다른 테스트의 컴포넌트 스캔에는 잡히지 않는다.
     */
    @TestComponent
    @RestController
    @RequiredArgsConstructor
    static class ParticipantProbeController {

        private final AppearanceUserService appearanceUserService;

        @GetMapping(PROBE_PATH)
        Long participant(@LoginUserId Long userId) {
            return appearanceUserService.getParticipant(userId).getId();
        }
    }
}
