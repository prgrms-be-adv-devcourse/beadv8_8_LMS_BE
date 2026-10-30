package com.hapbang.appearance.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.global.config.SecurityConfig;

// Gateway 사용자 헤더 인증(#395)을 실제 SecurityConfig로 확인한다. 컨트롤러는 테스트 전용이다.
@ContextConfiguration(classes = com.hapbang.testsupport.WebSliceTestConfiguration.class)
@WebMvcTest(controllers = GatewayUserHeaderFilterTest.WhoAmIController.class,
        properties = "spring.config.name=hapbang-module-test")
@Import({SecurityConfig.class, GatewayUserHeaderFilterTest.WhoAmIController.class})
@ExtendWith(OutputCaptureExtension.class)
class GatewayUserHeaderFilterTest {

    private static final String PATH = "/api/v1/samples/me";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("X-User-Id·X-User-Role이 맞으면 getName() = 회원 ID, 권한 = ROLE_{역할} 하나로 인증한다")
    void authenticated() throws Exception {
        mockMvc.perform(withUser(get(PATH), "7", "INFLUENCER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("7"))
                .andExpect(jsonPath("$.authorities.length()").value(1))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_INFLUENCER"));
    }

    @Test
    @DisplayName("헤더가 없으면 익명이라 로그인이 필요한 API는 401")
    void noHeaders() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "[{index}] id={0}, role={1}")
    @CsvSource(value = {
            "7, NULL", "NULL, INFLUENCER",
            "abc, INFLUENCER", "0, INFLUENCER", "-1, INFLUENCER", "'', INFLUENCER", "' 7', INFLUENCER",
            "7, ROLE_INFLUENCER", "7, influencer", "7, SUPER", "7, ''"
    }, nullValues = "NULL")
    @DisplayName("한쪽만 있거나 형식이 맞지 않으면 인증하지 않아 401, 경고 로그를 남긴다")
    void malformed(String userId, String role, CapturedOutput output) throws Exception {
        mockMvc.perform(withUser(get(PATH), userId, role)).andExpect(status().isUnauthorized());

        assertThat(output.getAll()).contains("사용자 헤더 형식 오류로 인증하지 않음");
    }

    @Test
    @DisplayName("CSRF 토큰 없이 POST할 수 있고, 세션·쿠키를 만들지 않는다")
    void statelessWithoutCsrf() throws Exception {
        MvcResult result = mockMvc.perform(withUser(post(PATH), "7", "USER"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    private static MockHttpServletRequestBuilder withUser(MockHttpServletRequestBuilder request, String userId,
                                                          String role) {
        if (userId != null) {
            request.header(GatewayUserHeaderFilter.USER_ID, userId);
        }
        if (role != null) {
            request.header(GatewayUserHeaderFilter.USER_ROLE, role);
        }
        return request;
    }

    record WhoAmI(String name, List<String> authorities) {
    }

    @org.springframework.boot.test.context.TestComponent
    @RestController
    static class WhoAmIController {

        @GetMapping(PATH)
        WhoAmI get() {
            return current();
        }

        @PostMapping(PATH)
        WhoAmI post() {
            return current();
        }

        private static WhoAmI current() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            return new WhoAmI(authentication.getName(),
                    authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
        }
    }
}
