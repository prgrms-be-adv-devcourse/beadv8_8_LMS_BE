package com.hapbang.coupon.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.filter.ServerHttpObservationFilter;

import com.hapbang.coupon.app.CouponIssueFacade;
import com.hapbang.coupon.app.CouponIssueRequestUseCase;
import com.hapbang.coupon.app.CouponSoldOutCache;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.in.dto.IssueRequestCreateRequest;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.jayway.jsonpath.JsonPath;

// 매진 빠른 경로: 발급 가능한 회원의 발급 접수만 매진 캐시에 묻고, 매진이면 예외 처리기와 같은 409를 바로 쓴다. 그 밖은 그대로 넘긴다.
// 정상 경로와 응답이 같은지는 발급 e2e가 실제 필터 체인으로 본다
class CouponSoldOutFastPathFilterTest {

    private static final long COUPON_ID = 42L;
    private static final String ISSUE_PATH = "/api/v1/coupons/" + COUPON_ID + "/issue-requests";

    private final CouponSoldOutCache soldOutCache = mock();
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CouponSoldOutFastPathFilter filter = new CouponSoldOutFastPathFilter(() -> soldOutCache, () -> meterRegistry,
            () -> List.of(new JacksonJsonHttpMessageConverter()));

    @Test
    void 매진이면_다음_필터로_넘기지_않고_COUPON_SOLD_OUT_409를_쓰고_매진으로_세며_uri_태그를_남긴다() throws Exception {
        when(soldOutCache.isSoldOut(COUPON_ID)).thenReturn(true);
        MockHttpServletRequest request = issueRequest("7", "USER");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var observation = new ServerRequestObservationContext(request, response);
        request.setAttribute(ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, observation);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        String body = response.getContentAsString();
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("COUPON_SOLD_OUT");
        assertThat((String) JsonPath.read(body, "$.detail")).isEqualTo(CouponErrorCode.COUPON_SOLD_OUT.getMessage());
        assertThat((Integer) JsonPath.read(body, "$.status")).isEqualTo(409);
        assertThat((String) JsonPath.read(body, "$.instance")).isEqualTo(ISSUE_PATH);
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Strict-Transport-Security")).isNull();
        assertThat(meterRegistry.get(CouponIssueRequestUseCase.SOLD_OUT_METRIC).counter().count()).isEqualTo(1);
        assertThat(observation.getPathPattern()).isEqualTo("/api/v1/coupons/{couponId}/issue-requests");
    }

    @Test
    void 매진이_아니면_그대로_넘긴다() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(issueRequest("7", "USER"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getContentAsString()).isEmpty();
        verify(soldOutCache).isSoldOut(COUPON_ID);
        assertThat(meterRegistry.find(CouponIssueRequestUseCase.SOLD_OUT_METRIC).counter()).isNull();
    }

    @Test
    void 매진_캐시에서_예외가_나면_정상_경로로_넘긴다() throws Exception {
        when(soldOutCache.isSoldOut(COUPON_ID)).thenThrow(new IllegalStateException("redis"));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(issueRequest("7", "USER"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getContentAsString()).isEmpty();
    }

    @ParameterizedTest(name = "{0} {1} userId={2} role={3}")
    @CsvSource(nullValues = "null", value = {
            "POST, /api/v1/coupons/42/issue-requests, null, USER",
            "POST, /api/v1/coupons/42/issue-requests, 7, null",
            "POST, /api/v1/coupons/42/issue-requests, 0, USER",
            "POST, /api/v1/coupons/42/issue-requests, -7, USER",
            "POST, /api/v1/coupons/42/issue-requests, abc, USER",
            "POST, /api/v1/coupons/42/issue-requests, 7, ADMIN",
            "POST, /api/v1/coupons/42/issue-requests, 7, user",
            "GET, /api/v1/coupons/42/issue-requests, 7, USER",
            "GET, /api/v1/coupons/42/issue-requests/req-1, 7, USER",
            "POST, /api/v1/coupons/admin/issue-requests, 7, USER",
            "POST, /api/v1/coupons/42/issue-requests/, 7, USER",
            "POST, /api/v1/coupons/1234567890123456789/issue-requests, 7, USER"
    })
    void 발급_가능한_회원의_발급_접수가_아니면_매진을_묻지_않고_넘긴다(String method, String path, String userId, String role)
            throws Exception {
        when(soldOutCache.isSoldOut(anyLong())).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (userId != null) {
            request.addHeader("X-User-Id", userId);
        }
        if (role != null) {
            request.addHeader("X-User-Role", role);
        }
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(soldOutCache, never()).isSoldOut(anyLong());
    }

    @Test
    void HTTPS_요청의_매진_응답에는_Security처럼_HSTS를_붙인다() throws Exception {
        when(soldOutCache.isSoldOut(COUPON_ID)).thenReturn(true);
        MockHttpServletRequest request = issueRequest("7", "VERIFIED_INFLUENCER");
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("Strict-Transport-Security")).isEqualTo("max-age=31536000 ; includeSubDomains");
    }

    @Test
    void 발급_가능한_역할은_발급_Facade의_역할_검사와_같다() {
        String roles = CouponSoldOutFastPathFilter.ISSUER_ROLES.stream().map(role -> "'" + role.name() + "'")
                .collect(Collectors.joining(","));

        assertThat(CouponIssueFacade.class.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAnyRole(" + roles + ")");
    }

    @Test
    void uri_태그는_컨트롤러의_발급_접수_매핑과_같다() throws Exception {
        String base = ApiV1CouponController.class.getAnnotation(RequestMapping.class).value()[0];
        String issue = ApiV1CouponController.class.getMethod("requestIssue", Long.class, Long.class,
                IssueRequestCreateRequest.class).getAnnotation(PostMapping.class).value()[0];

        assertThat(CouponSoldOutFastPathFilter.URI_PATTERN).isEqualTo(base + issue);
    }

    private static MockHttpServletRequest issueRequest(String userId, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ISSUE_PATH);
        request.addHeader("X-User-Id", userId);
        request.addHeader("X-User-Role", role);
        return request;
    }
}
