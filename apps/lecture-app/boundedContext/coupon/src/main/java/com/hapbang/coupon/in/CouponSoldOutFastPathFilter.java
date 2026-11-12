package com.hapbang.coupon.in;

import java.io.IOException;
import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ServerHttpObservationFilter;

import com.hapbang.coupon.app.CouponIssueRequestUseCase;
import com.hapbang.coupon.app.CouponSoldOutCache;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponExceptionHandler;
import com.hapbang.shared.member.domain.Role;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 매진 빠른 경로. 발급 접수({@code POST /api/v1/coupons/{couponId}/issue-requests})가 Gateway 사용자 헤더로 발급 가능한 회원이고
 * 매진 표시 캐시({@link CouponSoldOutCache})가 매진이라고 하면, Spring Security·MVC를 거치지 않고 예외 처리기와 같은 409
 * {@code COUPON_SOLD_OUT}을 바로 쓴다. 선착순 오픈 직후 몰리는 매진 요청의 CPU를 줄여 접수 요청이 CPU를 쓰게 한다. 그 밖의 요청과
 * 매진이 아닌 요청은 그대로 넘긴다. 최종 판정은 지금처럼 발급 Lua다.
 * <p>
 * 정상 경로와 다른 점: 요청 본문을 읽지 않으므로 형식이 틀린 요청 ID도 매진이면 409다. 사용자 헤더 필터보다 앞이라 이 응답의 로그에는
 * MDC {@code userId}가 없다(이 필터는 로그를 남기지 않는다. 건수는 {@code coupon.issue.sold_out}이 센다).
 */
public class CouponSoldOutFastPathFilter extends OncePerRequestFilter {

    /** 컨트롤러의 매핑 경로. {@code http_server_requests}의 uri 태그를 정상 경로와 같게 남긴다 */
    static final String URI_PATTERN = "/api/v1/coupons/{couponId}/issue-requests";

    /** Gateway 사용자 헤더. 강의 앱의 사용자 헤더 필터와 같은 이름이다(쿠폰은 강의 코드를 import하지 않는다) */
    static final String USER_ID = "X-User-Id";
    static final String USER_ROLE = "X-User-Role";

    /** 발급 접수를 허용하는 역할. 발급 Facade의 {@code @PreAuthorize}와 같다(테스트가 둘이 같은지 본다) */
    static final Set<Role> ISSUER_ROLES = EnumSet.of(Role.USER, Role.INFLUENCER, Role.VERIFIED_INFLUENCER);

    /**
     * 정상 경로의 409에 Spring Security가 붙이는 기본 보안 헤더. 강의 앱 보안 설정은 headers를 바꾸지 않는다. HSTS는 Security처럼 HTTPS
     * 요청에만 붙인다. 정상 경로와 같은지는 발급 e2e가 본다
     */
    private static final Map<String, String> SECURITY_HEADERS = Map.of(
            "X-Content-Type-Options", "nosniff",
            "X-XSS-Protection", "0",
            HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate",
            HttpHeaders.PRAGMA, "no-cache",
            HttpHeaders.EXPIRES, "0",
            "X-Frame-Options", "DENY");
    private static final String HSTS = "Strict-Transport-Security";
    private static final String HSTS_VALUE = "max-age=31536000 ; includeSubDomains";

    /** 쿠폰 ID는 Long 범위 안의 숫자만 본다. 그 밖은 정상 경로가 응답한다 */
    private static final Pattern PATH = Pattern.compile("/api/v1/coupons/(\\d{1,18})/issue-requests");

    /** 필터 등록은 서블릿 컨텍스트가 뜰 때라 일반 빈보다 먼저다. 캐시(Redis 연결)·지표는 첫 요청 때 꺼내 앞당겨 만들지 않는다 */
    private final SingletonSupplier<CouponSoldOutCache> soldOutCache;
    private final SingletonSupplier<Counter> soldOut;
    /** MVC가 ProblemDetail을 쓰는 변환기. 본문 직렬화를 예외 처리기 응답과 같게 한다 */
    private final SingletonSupplier<HttpMessageConverter<Object>> problemWriter;

    public CouponSoldOutFastPathFilter(Supplier<CouponSoldOutCache> soldOutCache, Supplier<MeterRegistry> meterRegistry,
                                       Supplier<List<HttpMessageConverter<?>>> mvcConverters) {
        this.soldOutCache = SingletonSupplier.of(soldOutCache);
        this.soldOut = SingletonSupplier.of(() -> meterRegistry.get().counter(CouponIssueRequestUseCase.SOLD_OUT_METRIC));
        this.problemWriter = SingletonSupplier.of(() -> problemWriter(mvcConverters.get()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Long couponId = issueRequestCouponId(request);
        if (couponId == null || !isSoldOut(couponId)) {
            chain.doFilter(request, response);
            return;
        }
        soldOut.obtain().increment();
        ServerHttpObservationFilter.findObservationContext(request).ifPresent(context -> context.setPathPattern(URI_PATTERN));
        writeSoldOut(request, response);
    }

    /** 매진 캐시를 보다 예외가 나면 매진이 아닌 것으로 보고 정상 경로(최종 판정은 Lua)에 맡긴다. 이 필터는 보안 앞이라 예외가 새면 공통 오류 응답을 거치지 못한다 */
    private boolean isSoldOut(long couponId) {
        try {
            return soldOutCache.obtain().isSoldOut(couponId);
        } catch (RuntimeException e) {
            logger.debug("매진 캐시를 보지 못해 정상 경로로 넘긴다 couponId=" + couponId, e);
            return false;
        }
    }

    /** 발급 가능한 회원의 발급 접수면 쿠폰 ID, 아니면 {@code null} */
    private static Long issueRequestCouponId(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return null;
        }
        Matcher path = PATH.matcher(request.getRequestURI());
        if (!path.matches() || !isIssuer(request.getHeader(USER_ID), request.getHeader(USER_ROLE))) {
            return null;
        }
        return Long.parseLong(path.group(1));
    }

    private static boolean isIssuer(String userId, String role) {
        if (userId == null || role == null) {
            return false;
        }
        try {
            return Long.parseLong(userId) > 0 && ISSUER_ROLES.contains(Role.valueOf(role));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void writeSoldOut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProblemDetail problem = CouponExceptionHandler.problemDetail(CouponErrorCode.COUPON_SOLD_OUT, null, Map.of());
        // MVC가 ProblemDetail 응답에 instance가 없으면 요청 경로를 넣는 것과 같게 한다
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(problem.getStatus());
        SECURITY_HEADERS.forEach(response::setHeader);
        if (request.isSecure()) {
            response.setHeader(HSTS, HSTS_VALUE);
        }
        problemWriter.obtain().write(problem, MediaType.APPLICATION_PROBLEM_JSON, new ServletServerHttpResponse(response));
    }

    @SuppressWarnings("unchecked")
    private static HttpMessageConverter<Object> problemWriter(List<HttpMessageConverter<?>> converters) {
        return (HttpMessageConverter<Object>) converters.stream()
                .filter(converter -> converter.canWrite(ProblemDetail.class, MediaType.APPLICATION_PROBLEM_JSON))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("ProblemDetail을 쓸 MVC 변환기가 없다"));
    }
}
