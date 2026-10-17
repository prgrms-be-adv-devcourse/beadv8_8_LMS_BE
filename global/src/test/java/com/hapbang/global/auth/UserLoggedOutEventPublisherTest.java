package com.hapbang.global.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;

class UserLoggedOutEventPublisherTest {

    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final UserLoggedOutEventPublisher handler = new UserLoggedOutEventPublisher(eventPublisher);

    // 이벤트 발행이 실패해도 로그아웃 처리기 체인(세션 무효화)이 끊기지 않아야 한다
    @Test
    void 회원_ID가_아닌_로그인_정보여도_예외_없이_넘어가고_발행하지_않는다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);

        assertThatCode(() -> handler.logout(request, new MockHttpServletResponse(),
                new TestingAuthenticationToken("not-a-member-id", null)))
                .doesNotThrowAnyException();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void 세션이_없으면_발행하지_않는다() {
        handler.logout(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new TestingAuthenticationToken("7", null));

        verifyNoInteractions(eventPublisher);
    }
}
