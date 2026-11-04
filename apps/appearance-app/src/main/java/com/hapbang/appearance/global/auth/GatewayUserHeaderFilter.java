package com.hapbang.appearance.global.auth;

import java.io.IOException;
import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.hapbang.shared.member.domain.Role;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Gateway(Envoy)가 JWT를 검증한 뒤 넘겨 준 사용자 헤더로 로그인 상태를 만든다 (#395).
 * <ul>
 *     <li>{@code X-User-Id}(회원 ID)와 {@code X-User-Role}(역할 하나)이 둘 다 맞으면 인증한다.
 *     {@code getName()}은 회원 ID({@code @LoginUserId}), 권한은 {@code ROLE_{역할}}({@code @PreAuthorize})이다.</li>
 *     <li>헤더가 없으면 익명으로 넘긴다. 로그인이 필요한지는 Security 설정과 {@code @PreAuthorize}가 정한다.</li>
 *     <li>한쪽만 있거나 값이 형식에 맞지 않으면 인증하지 않고 넘겨(익명) 로그인이 필요한 API는 401이 된다.
 *     Gateway는 검증한 claim만 헤더로 만들므로 이런 요청은 설정 오류다. 경고 로그를 남긴다.</li>
 *     <li>서명은 다시 검증하지 않는다. Gateway가 외부 요청의 {@code X-User-*}를 먼저 지우고,
 *     NetworkPolicy가 외부 API 포트(8080)에 Gateway만 들어오게 한다 (k8s {@code networkpolicy.yaml}).</li>
 *     <li>헤더의 역할은 토큰 발급 시점 값이라 최대 15분 늦다. 정지·역할 변경은 복제한 회원 정보로 다시 확인한다
 *     ({@code AppearanceUser.validateCanAppear}, {@code validateIsAdmin}).</li>
 * </ul>
 */
@Slf4j
public class GatewayUserHeaderFilter extends OncePerRequestFilter {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_ROLE = "X-User-Role";

    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String userId = request.getHeader(USER_ID);
        String role = request.getHeader(USER_ROLE);
        if (userId != null || role != null) {
            authenticate(request, userId, role);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String userId, String role) {
        Long id = parseUserId(userId);
        Role parsedRole = parseRole(role);
        if (id == null || parsedRole == null) {
            log.warn("사용자 헤더 형식 오류로 인증하지 않음: {} {} ({}={}, {}={})", request.getMethod(), request.getRequestURI(),
                    USER_ID, userId, USER_ROLE, role);
            return;
        }
        var authentication = new PreAuthenticatedAuthenticationToken(String.valueOf(id), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + parsedRole.name())));
        var context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
    }

    private static Long parseUserId(String value) {
        if (value == null) {
            return null;
        }
        try {
            long id = Long.parseLong(value);
            return id > 0 ? id : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Role parseRole(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Role.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
