package com.hapbang.member.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;

import lombok.RequiredArgsConstructor;

/** 회원 모듈 진입점 */
@Service
@RequiredArgsConstructor
public class MemberFacade {

    private final MemberOAuthLoginUseCase memberOAuthLoginUseCase;

    /** OAuth 로그인 (처음이면 가입) */
    @Transactional
    public User oauthLogin(OAuthProvider provider, String providerUserId, String email, String nickname) {
        return memberOAuthLoginUseCase.login(provider, providerUserId, email, nickname);
    }
}
