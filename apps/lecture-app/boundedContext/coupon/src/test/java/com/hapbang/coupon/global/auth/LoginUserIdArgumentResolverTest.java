package com.hapbang.coupon.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

// 강의 리졸버와 같은 동작인지 본다: 쿠폰 애노테이션이 붙은 Long만 처리하고, 비로그인이면 인증 예외(공통 핸들러가 401)다
class LoginUserIdArgumentResolverTest {

    private final LoginUserIdArgumentResolver resolver = new LoginUserIdArgumentResolver();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 쿠폰_LoginUserId가_붙은_Long_파라미터만_처리한다() throws Exception {
        assertThat(resolver.supportsParameter(parameter(0))).isTrue();
        assertThat(resolver.supportsParameter(parameter(1))).isFalse();
    }

    @Test
    void 로그인_상태면_로그인_정보의_이름을_회원_ID로_돌려준다() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("7", null, List.of()));

        assertThat(resolver.resolveArgument(parameter(0), null, null, null)).isEqualTo(7L);
    }

    @Test
    void 로그인_정보가_없거나_익명이면_인증_예외다() throws Exception {
        assertThatThrownBy(() -> resolver.resolveArgument(parameter(0), null, null, null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);

        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThatThrownBy(() -> resolver.resolveArgument(parameter(0), null, null, null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    private MethodParameter parameter(int index) throws NoSuchMethodException {
        Method method = SampleController.class.getDeclaredMethod("handle", Long.class, Long.class);
        return new MethodParameter(method, index);
    }

    static class SampleController {
        void handle(@LoginUserId Long memberId, Long other) {
        }
    }
}
