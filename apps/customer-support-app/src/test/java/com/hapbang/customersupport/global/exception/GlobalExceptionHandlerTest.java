package com.hapbang.customersupport.global.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.hapbang.customersupport.global.config.SecurityConfig;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.TestLogin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 공통 핸들러가 모든 오류를 ProblemDetail + code로 응답하는지 검증한다. 컨트롤러는 테스트 전용이다.
@ContextConfiguration(classes = com.hapbang.testsupport.WebSliceTestConfiguration.class)
@WebMvcTest(controllers = GlobalExceptionHandlerTest.SampleController.class, properties = {
        "spring.config.name=hapbang-module-test",
        // oauth2Login()이 구글 등록 정보를 요구한다
        "spring.security.oauth2.client.registration.google.client-id=test",
        "spring.security.oauth2.client.registration.google.client-secret=test"
})
@Import({SecurityConfig.class, GlobalExceptionHandler.class, GlobalExceptionHandlerTest.SampleController.class})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    // 업무 예외

    @Test
    void 모듈_오류_코드는_그_상태_코드_기본_메시지로_응답한다() throws Exception {
        mockMvc.perform(asUser(get("/api/v1/samples/module-code")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("SAMPLE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("샘플을 찾을 수 없습니다."));
    }

    @Test
    void 상세_메시지를_주면_detail만_바뀌고_코드는_같다() throws Exception {
        mockMvc.perform(asUser(get("/api/v1/samples/module-code-detail")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SAMPLE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("샘플 7번을 찾을 수 없습니다."));
    }

    @Test
    void 하위_예외의_추가_속성을_응답에_싣는다() throws Exception {
        mockMvc.perform(asUser(get("/api/v1/samples/refused")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAMPLE_ALREADY_CLOSED"))
                .andExpect(jsonPath("$.reason").value("PERIOD_EXPIRED"));
    }

    // 요청 값 검증·형식 오류

    @Test
    void 요청_본문_검증에_실패하면_400과_항목별_오류를_담는다() throws Exception {
        mockMvc.perform(asUser(post("/api/v1/samples")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": " ", "count": 0, "type": "BASIC"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value("요청 값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'count')]").exists());
    }

    @Test
    void 요청_파라미터_제약_검증에_실패하면_400과_파라미터_이름을_담는다() throws Exception {
        mockMvc.perform(asUser(get("/api/v1/samples")).param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void 경로_값의_타입이_다르면_400과_항목을_알려_준다() throws Exception {
        mockMvc.perform(asUser(get("/api/v1/samples/abc")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("sampleId"))
                .andExpect(jsonPath("$.detail").value(containsString("'abc'")));
    }

    @Test
    void 요청_파라미터가_enum에_없는_값이면_사용_가능한_값을_알려_준다() throws Exception {
        mockMvc.perform(asUser(get("/api/v1/samples")).param("type", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("type"))
                .andExpect(jsonPath("$.detail").value(containsString("[BASIC, PREMIUM]")));
    }

    @Test
    void 필수_요청_파라미터가_없으면_400과_파라미터_이름을_담는다() throws Exception {
        mockMvc.perform(asUser(get("/api/v1/samples/required")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("keyword"));
    }

    @Test
    void 요청_본문의_enum_값이_없는_값이면_필드와_사용_가능한_값을_알려_준다() throws Exception {
        mockMvc.perform(asUser(post("/api/v1/samples")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "샘플", "count": 1, "type": "UNKNOWN"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("type"))
                .andExpect(jsonPath("$.detail").value(containsString("[BASIC, PREMIUM]")));
    }

    @Test
    void 요청_본문의_값_타입이_다르면_필드를_알려_준다() throws Exception {
        mockMvc.perform(asUser(post("/api/v1/samples")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "샘플", "count": "많이", "type": "BASIC"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("count"));
    }

    @Test
    void 요청_본문이_JSON_문법에_맞지_않으면_400() throws Exception {
        mockMvc.perform(asUser(post("/api/v1/samples")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    // 권한·Spring 예외·처리하지 못한 예외

    @Test
    void 메서드_보안_거절은_403_FORBIDDEN으로_응답한다() throws Exception {
        mockMvc.perform(asUser(get("/api/v1/samples/secured")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void ResponseStatusException은_그_상태와_상태에_맞는_코드로_응답한다() throws Exception {
        mockMvc.perform(asUser(get("/api/v1/samples/status")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 지원하지_않는_메서드는_405와_코드로_응답한다() throws Exception {
        mockMvc.perform(asUser(delete("/api/v1/samples/required")).with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void 처리하지_못한_예외는_500으로_응답하고_내부_메시지를_노출하지_않는다() throws Exception {
        mockMvc.perform(asUser(get("/api/v1/samples/unexpected")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value(GlobalErrorCode.INTERNAL_ERROR.getMessage()))
                .andExpect(content().string(not(containsString("내부 비밀"))));
    }

    private MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request) {
        return request.with(TestLogin.as(1L, Role.USER));
    }

    /** 모듈 오류 코드 예시 (각 모듈의 {Module}ErrorCode) */
    @Getter
    @RequiredArgsConstructor
    enum SampleErrorCode implements BaseErrorCode {
        SAMPLE_NOT_FOUND(HttpStatus.NOT_FOUND, "샘플을 찾을 수 없습니다."),
        SAMPLE_ALREADY_CLOSED(HttpStatus.CONFLICT, "이미 닫힌 샘플입니다.");

        private final HttpStatus status;
        private final String message;
    }

    /** 응답에 추가 속성을 싣는 하위 예외 예시 */
    static class SampleRefusedException extends BusinessException {

        SampleRefusedException(String reason) {
            super(SampleErrorCode.SAMPLE_ALREADY_CLOSED);
            addProperty("reason", reason);
        }
    }

    enum SampleType {
        BASIC, PREMIUM
    }

    record SampleRequest(
            @NotBlank(message = "이름은 필수입니다.") String name,
            @Min(value = 1, message = "개수는 1 이상이어야 합니다.") int count,
            @NotNull SampleType type) {
    }

    @org.springframework.boot.test.context.TestComponent
    @RestController
    static class SampleController {

        @GetMapping("/api/v1/samples/module-code")
        void moduleCode() {
            throw new BusinessException(SampleErrorCode.SAMPLE_NOT_FOUND);
        }

        @GetMapping("/api/v1/samples/module-code-detail")
        void moduleCodeDetail() {
            throw new BusinessException(SampleErrorCode.SAMPLE_NOT_FOUND, "샘플 7번을 찾을 수 없습니다.");
        }

        @GetMapping("/api/v1/samples/refused")
        void refused() {
            throw new SampleRefusedException("PERIOD_EXPIRED");
        }

        @PostMapping("/api/v1/samples")
        void create(@Valid @RequestBody SampleRequest request) {
        }

        @GetMapping("/api/v1/samples")
        void list(@RequestParam(name = "size", defaultValue = "20") @Min(1) int size,
                @RequestParam(name = "type", required = false) SampleType type) {
        }

        @GetMapping("/api/v1/samples/{sampleId}")
        void detail(@PathVariable("sampleId") Long sampleId) {
        }

        @GetMapping("/api/v1/samples/required")
        void required(@RequestParam(name = "keyword") String keyword) {
        }

        @PreAuthorize("hasRole('ADMIN')")
        @GetMapping("/api/v1/samples/secured")
        void secured() {
        }

        @GetMapping("/api/v1/samples/status")
        void status() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "테스트 계정이 없습니다.");
        }

        @GetMapping("/api/v1/samples/unexpected")
        void unexpected() {
            throw new IllegalStateException("내부 비밀 정보가 담긴 메시지");
        }
    }
}
