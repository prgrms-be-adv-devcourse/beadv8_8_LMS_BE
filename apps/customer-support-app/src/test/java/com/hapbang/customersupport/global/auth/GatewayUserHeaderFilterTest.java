package com.hapbang.customersupport.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
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

import com.hapbang.customersupport.global.config.SecurityConfig;

// Gateway 사용자 헤더 인증을 실제 SecurityConfig로 확인한다. 컨트롤러는 테스트 전용이다.
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
    void 사용자_헤더가_맞으면_회원_ID와_역할_권한으로_인증한다() throws Exception {
        mockMvc.perform(withUser(get(PATH), "7", "INFLUENCER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("7"))
                .andExpect(jsonPath("$.authorities.length()").value(1))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_INFLUENCER"))
                .andExpect(jsonPath("$.mdcUserId").value("7"));

        // 요청 스레드는 다시 쓰이므로 요청이 끝나면 MDC의 회원 ID를 지운다 (#582)
        assertThat(MDC.get(GatewayUserHeaderFilter.MDC_USER_ID)).isNull();
    }

    @Test
    void 헤더가_없으면_익명이라_로그인이_필요한_API는_401이다() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "[{index}] id={0}, role={1}")
    @CsvSource(value = {
            "7, NULL", "NULL, INFLUENCER",
            "abc, INFLUENCER", "0, INFLUENCER", "-1, INFLUENCER", "'', INFLUENCER", "' 7', INFLUENCER",
            "9223372036854775808, INFLUENCER",
            "7, ROLE_INFLUENCER", "7, influencer", "7, SUPER", "7, ''"
    }, nullValues = "NULL")
    void 헤더가_한쪽만_있거나_형식이_틀리면_인증하지_않고_경고_로그를_남긴다(String userId, String role, CapturedOutput output)
            throws Exception {
        mockMvc.perform(withUser(get(PATH), userId, role)).andExpect(status().isUnauthorized());

        assertThat(output.getAll()).contains("사용자 헤더 형식 오류로 인증하지 않음");
    }

    @Test
    void CSRF_토큰_없이_POST하고_세션과_쿠키를_만들지_않는다() throws Exception {
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

    record WhoAmI(String name, List<String> authorities, String mdcUserId) {
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
                    authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList(),
                    MDC.get(GatewayUserHeaderFilter.MDC_USER_ID));
        }
    }
}
