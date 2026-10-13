package com.hapbang.appearance.global.exception;

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
import com.hapbang.global.exception.GlobalExceptionHandler;

/**
 * 테스트 전용 컨트롤러로 출연 예외가 Problem Detail로 바뀌는지 확인한다. 스프링 전체를 띄우지 않는다.
 */
class AppearanceExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("출연 예외는 오류 코드의 상태, code, detail을 담은 Problem Detail로 응답한다")
    void handlesAppearanceException() throws Exception {
        mockMvc.perform(get("/test/error"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("NOT_INFLUENCER"))
                .andExpect(jsonPath("$.detail").value(AppearanceErrorCode.NOT_INFLUENCER.getMessage()));
    }

    @Test
    @DisplayName("상세 메시지를 따로 주면 detail에 그 메시지가 담긴다")
    void customDetail() throws Exception {
        mockMvc.perform(get("/test/error-detail"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value("테스트 오류"));
    }

    @RestController
    static class TestController {


        @GetMapping("/test/error")
        void error() {
            throw new AppearanceException(AppearanceErrorCode.NOT_INFLUENCER);
        }

        @GetMapping("/test/error-detail")
        void errorDetail() {
            throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST, "테스트 오류");
        }
    }
}
