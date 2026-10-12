package com.hapbang.member.in;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;

/** 구글 로그인 입구 (Spring Security가 호출 → MemberFacade로 로그인·가입) */
@Component
public class MemberOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final MemberFacade memberFacade;
    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;

    @Autowired
    public MemberOAuth2UserService(MemberFacade memberFacade) {
        this(memberFacade, new DefaultOAuth2UserService());
    }

    /** 테스트용: 구글 호출 객체를 가짜로 바꿔 넣는다 */
    MemberOAuth2UserService(MemberFacade memberFacade,
                            OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate) {
        this.memberFacade = memberFacade;
        this.delegate = delegate;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) {
        OAuth2User googleUser = delegate.loadUser(userRequest);   // 구글에서 사용자 정보 조회

        User user = memberFacade.oauthLogin(
                OAuthProvider.GOOGLE,
                googleUser.getName(),                // sub (구글 회원 고유 ID)
                googleUser.getAttribute("email"),
                googleUser.getAttribute("name"));

        return new MemberOAuth2User(user.getId(), user.getRole(), googleUser.getAttributes());
    }
}
