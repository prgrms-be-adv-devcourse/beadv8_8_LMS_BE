package com.hapbang.chat.global.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.hapbang.shared.internal.InternalApiHeaders;

// 내부 API 키 필터 (#300). 맞는 키만 내부 호출로 인증하고, 실패 로그에는 보낸 키 값을 남기지 않는다.
@ExtendWith(OutputCaptureExtension.class)
class InternalApiKeyFilterTest {

    private static final String KEY = "expected-internal-key";

    private final InternalApiKeyFilter filter = new InternalApiKeyFilter(KEY);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 맞는_키면_내부_호출로_인증한다() throws Exception {
        filter.doFilter(request(KEY), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_INTERNAL");
    }

    @Test
    void 틀린_키는_인증하지_않고_로그에_키_값을_남기지_않는다(CapturedOutput output) throws Exception {
        filter.doFilter(request("guessed-key-value"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(output).contains("내부 API 인증 실패", "불일치")
                .doesNotContain("guessed-key-value")
                .doesNotContain(KEY);
    }

    private static MockHttpServletRequest request(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/internal/lectures/earnings");
        request.addHeader(InternalApiHeaders.API_KEY, key);
        return request;
    }
}
