package com.hapbang.member.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.event.UserLoggedOutEvent;
import com.hapbang.testsupport.TestLogin;

// 실제 SecurityConfig를 거쳐 공통 권한 규칙을 검증한다. 컨트롤러는 테스트 전용이다.
@ContextConfiguration(classes = com.hapbang.testsupport.WebSliceTestConfiguration.class)
@WebMvcTest(controllers = SecurityConfigTest.SampleController.class, properties = {
        "spring.config.name=hapbang-module-test",
        // oauth2Login()이 구글 등록 정보를 요구한다
        "spring.security.oauth2.client.registration.google.client-id=test",
        "spring.security.oauth2.client.registration.google.client-secret=test"
})
@Import({SecurityConfig.class, SecurityConfigTest.SampleController.class})
@RecordApplicationEvents
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationEvents events;

    @Test
    void 로그아웃하면_회원_ID와_끝나는_세션_ID로_로그아웃_이벤트를_발행한다() throws Exception {
        MockHttpSession session = new MockHttpSession(null, "session-1");

        mockMvc.perform(post("/api/v1/members/logout").session(session).with(TestLogin.as(7L, Role.INFLUENCER))
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(events.stream(UserLoggedOutEvent.class)).containsExactly(new UserLoggedOutEvent(7L, "session-1"));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void 로그인하지_않은_로그아웃은_이벤트를_발행하지_않는다() throws Exception {
        mockMvc.perform(post("/api/v1/members/logout").with(csrf()))
                .andExpect(status().isOk());

        assertThat(events.stream(UserLoggedOutEvent.class)).isEmpty();
    }

    @Test
    void 관리자_API는_로그인하지_않으면_401() throws Exception {
        mockMvc.perform(get("/api/v1/samples/admin/ping"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 관리자_API는_관리자가_아니면_403() throws Exception {
        mockMvc.perform(get("/api/v1/samples/admin/ping").with(TestLogin.as(1L, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 관리자_API는_관리자면_통과한다() throws Exception {
        mockMvc.perform(get("/api/v1/samples/admin/ping").with(TestLogin.as(1L, Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void PreAuthorize의_역할이_아니면_403() throws Exception {
        mockMvc.perform(get("/api/v1/samples/verified").with(TestLogin.as(1L, Role.USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void PreAuthorize의_역할이면_통과한다() throws Exception {
        mockMvc.perform(get("/api/v1/samples/verified").with(TestLogin.as(1L, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isOk());
    }

    @Test
    void 강의_목록은_비회원도_조회한다() throws Exception {
        mockMvc.perform(get("/api/v1/lectures"))
                .andExpect(status().isOk());
    }

    @Test
    void 강의_상세와_등록은_비회원이면_401() throws Exception {
        mockMvc.perform(get("/api/v1/lectures/1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/lectures").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 헬스체크는_비회원도_조회한다() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void 헬스체크_하위_경로도_비회원이_조회한다() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
    }

    @Test
    void 헬스체크_외_actuator_경로는_비회원이면_401() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized());
    }

    @org.springframework.boot.test.context.TestComponent
    @RestController
    static class SampleController {

        @GetMapping("/api/v1/lectures")
        String lectures() {
            return "ok";
        }

        @GetMapping("/api/v1/lectures/{lectureId}")
        String lecture(@PathVariable("lectureId") Long lectureId) {
            return "ok";
        }

        @PostMapping("/api/v1/lectures")
        String registerLecture() {
            return "ok";
        }

        @PreAuthorize("hasRole('VERIFIED_INFLUENCER')")
        @GetMapping("/api/v1/samples/verified")
        String verifiedOnly() {
            return "ok";
        }

        @GetMapping("/api/v1/samples/admin/ping")
        String adminPing() {
            return "ok";
        }

        // 실제 actuator 엔드포인트 대신 같은 경로를 두고 보안 규칙만 확인한다
        @GetMapping({"/actuator/health", "/actuator/health/liveness"})
        String health() {
            return "ok";
        }

        @GetMapping("/actuator/env")
        String env() {
            return "ok";
        }
    }
}
