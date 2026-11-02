package com.hapbang.member.in;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 구글 로그인 뒤 받은 구글 토큰(OAuth2AuthorizedClient)을 보관하지 않는다 (#370).
 * 로그인할 때 사용자 정보만 한 번 읽고 그 뒤로 구글 API를 부르지 않는다.
 * Spring 기본값은 세션이 없으면 회원별로 서버 메모리(InMemoryOAuth2AuthorizedClientService)에 쌓아 두어, 세션을 없애면서 바꾼다
 */
public class NoOpOAuth2AuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
                                                                     Authentication principal,
                                                                     HttpServletRequest request) {
        return null;
    }

    @Override
    public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
                                     HttpServletRequest request, HttpServletResponse response) {
        // 보관하지 않는다
    }

    @Override
    public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
                                       HttpServletRequest request, HttpServletResponse response) {
        // 보관한 것이 없다
    }
}
