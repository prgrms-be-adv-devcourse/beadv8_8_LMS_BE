package com.hapbang.appearance.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.support.GatewayLogin;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.event.UserLoggedOutEvent;

// 실제 SecurityConfig를 거쳐 공통 권한 규칙을 검증한다. 컨트롤러는 테스트 전용이다.
@ContextConfiguration(classes = com.hapbang.testsupport.WebSliceTestConfiguration.class)
@WebMvcTest(controllers = SecurityConfigTest.SampleController.class, properties = "spring.config.name=hapbang-module-test")
@Import({SecurityConfig.class, SecurityConfigTest.SampleController.class})
@RecordApplicationEvents
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationEvents events;

    @Test
    void 회원_로그아웃_경로는_제공하지_않는다() throws Exception {
        mockMvc.perform(post("/api/v1/members/logout").with(GatewayLogin.as(7L, Role.INFLUENCER)))
                .andExpect(status().isNotFound());

        assertThat(events.stream(UserLoggedOutEvent.class)).isEmpty();
    }

    @Test
    void 비회원의_회원_로그아웃_경로_요청은_401이다() throws Exception {
        mockMvc.perform(post("/api/v1/members/logout"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 관리자_API는_로그인하지_않으면_401() throws Exception {
        mockMvc.perform(get("/api/v1/samples/admin/ping"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 관리자_API는_관리자가_아니면_403() throws Exception {
        mockMvc.perform(get("/api/v1/samples/admin/ping").with(GatewayLogin.as(1L, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 관리자_API는_관리자면_통과한다() throws Exception {
        mockMvc.perform(get("/api/v1/samples/admin/ping").with(GatewayLogin.as(1L, Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void PreAuthorize의_역할이_아니면_403() throws Exception {
        mockMvc.perform(get("/api/v1/samples/verified").with(GatewayLogin.as(1L, Role.USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void PreAuthorize의_역할이면_통과한다() throws Exception {
        mockMvc.perform(get("/api/v1/samples/verified").with(GatewayLogin.as(1L, Role.VERIFIED_INFLUENCER)))
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
        mockMvc.perform(post("/api/v1/lectures"))
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
