package com.hapbang.global.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.hapbang.shared.internal.InternalApiHeaders;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 내부 API 키가 맞으면 내부 호출({@code ROLE_INTERNAL})로 인증한다. 없거나 틀리면 인증하지 않고 넘겨 401이 된다 (#300).
 * 키는 상수 시간으로 비교하고 로그에 남기지 않는다.
 */
@Slf4j
class InternalApiKeyFilter extends OncePerRequestFilter {

    static final String ROLE = "INTERNAL";

    private final byte[] expectedKey;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    InternalApiKeyFilter(String expectedKey) {
        this.expectedKey = expectedKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(InternalApiHeaders.API_KEY);
        if (key != null && MessageDigest.isEqual(expectedKey, key.getBytes(StandardCharsets.UTF_8))) {
            var authentication = new PreAuthenticatedAuthenticationToken("internal", null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + ROLE)));
            var context = contextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            contextHolder.setContext(context);
        } else {
            log.warn("내부 API 인증 실패: {} {} (키 {})", request.getMethod(), request.getRequestURI(),
                    key == null ? "없음" : "불일치");
        }
        chain.doFilter(request, response);
    }
}
