package com.hapbang.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class LoginUserIdArgumentResolverTest {

    LoginUserIdArgumentResolver resolver = new LoginUserIdArgumentResolver();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("@LoginUserId가 붙은 Long 파라미터를 처리한다")
    void supports_annotated() throws Exception {
        assertThat(resolver.supportsParameter(parameter(0))).isTrue();
    }

    @Test
    @DisplayName("@LoginUserId가 없는 파라미터는 처리하지 않는다")
    void supports_notAnnotated() throws Exception {
        assertThat(resolver.supportsParameter(parameter(1))).isFalse();
    }

    @Test
    @DisplayName("로그인 상태면 로그인 정보의 이름(회원 ID)을 Long으로 반환한다")
    void resolve_loggedIn() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("1", null, List.of()));

        assertThat(resolver.resolveArgument(parameter(0), null, null, null)).isEqualTo(1L);
    }

    @Test
    @DisplayName("비로그인(익명) 상태면 인증 예외를 던진다")
    void resolve_anonymous() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThatThrownBy(() -> resolver.resolveArgument(parameter(0), null, null, null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    private MethodParameter parameter(int index) throws NoSuchMethodException {
        Method method = TestController.class.getDeclaredMethod("handle", Long.class, Long.class);
        return new MethodParameter(method, index);
    }

    static class TestController {
        void handle(@LoginUserId Long userId, Long other) {
        }
    }
}
