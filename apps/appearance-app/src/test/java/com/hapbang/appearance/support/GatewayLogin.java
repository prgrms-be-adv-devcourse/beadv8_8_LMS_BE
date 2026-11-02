package com.hapbang.appearance.support;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.appearance.global.auth.GatewayUserHeaderFilter;
import com.hapbang.shared.member.domain.Role;

/**
 * MockMvc 테스트에서 로그인 상태 만들기 (#395). 예: {@code .with(GatewayLogin.as(1L, Role.USER))}
 * <p>
 * 운영에서 Gateway가 넘기는 사용자 헤더를 실어, 보안 설정의 {@link GatewayUserHeaderFilter}를 그대로 거친다.
 * 팀 공통 {@code TestLogin}(OAuth2 로그인 흉내)은 필터를 거치지 않고 인증 정보를 직접 넣어서 출연 테스트에서는 이것을 쓴다.
 * E2E(실제 포트)는 {@link E2ELogin}을 쓴다.
 */
public final class GatewayLogin {

    private GatewayLogin() {
    }

    /** 이 회원 ID·역할로 로그인한 요청. {@code @LoginUserId}에는 userId가 들어간다 */
    public static RequestPostProcessor as(Long userId, Role role) {
        return request -> {
            request.addHeader(GatewayUserHeaderFilter.USER_ID, String.valueOf(userId));
            request.addHeader(GatewayUserHeaderFilter.USER_ROLE, role.name());
            return request;
        };
    }
}
