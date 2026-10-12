package com.hapbang.appearance.global.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.global.exception.AppearanceExceptionHandler;

/**
 * 테스트 전용 컨트롤러로 {@code @LoginMember}와 Problem Detail 응답을 확인한다. 스프링 전체를 띄우지 않는다.
 */
class LoginMemberAndProblemDetailTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new AppearanceExceptionHandler())
            .setCustomArgumentResolvers(new LoginMemberArgumentResolver())
            .build();

    @Test
    @DisplayName("X-User-Id 헤더의 회원 ID가 @LoginMember 파라미터로 들어온다")
    void resolvesLoginMember() throws Exception {
        mockMvc.perform(get("/test/me").header(AppearanceHeaders.USER_ID, "7"))
                .andExpect(status().isOk())
                .andExpect(content().string("7"));
    }

    @Test
    @DisplayName("헤더가 없으면 401 LOGIN_REQUIRED를 Problem Detail로 응답한다")
    void loginRequired() throws Exception {
        mockMvc.perform(get("/test/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    @Test
    @DisplayName("회원 ID가 숫자가 아니면 400 INVALID_REQUEST를 응답한다")
    void invalidMemberId() throws Exception {
        mockMvc.perform(get("/test/me").header(AppearanceHeaders.USER_ID, "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("AppearanceException은 오류 코드의 상태와 code로 응답한다")
    void handlesAppearanceException() throws Exception {
        mockMvc.perform(get("/test/error"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value("테스트 오류"));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/me")
        Long me(@LoginMember Long memberId) {
            return memberId;
        }

        @GetMapping("/test/error")
        void error() {
            throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST, "테스트 오류");
        }
    }
}
