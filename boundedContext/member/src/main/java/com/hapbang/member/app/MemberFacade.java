package com.hapbang.member.app;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;

import lombok.RequiredArgsConstructor;

/** 회원 모듈 진입점 */
@Service
@RequiredArgsConstructor
public class MemberFacade {

    private final MemberOAuthLoginUseCase memberOAuthLoginUseCase;
    private final MemberTestAccountUseCase memberTestAccountUseCase;
    private final MemberUpdateProfileUseCase memberUpdateProfileUseCase;
    private final MemberSupport memberSupport;

    /** OAuth 로그인 (처음이면 가입) */
    @Transactional
    public User oauthLogin(OAuthProvider provider, String providerUserId, String email, String nickname) {
        return memberOAuthLoginUseCase.login(provider, providerUserId, email, nickname);
    }

    /** 역할별 테스트 계정 만들기 (이미 있으면 건너뜀) */
    @Transactional
    public void createTestAccounts() {
        memberTestAccountUseCase.createAll();
    }

    /** 역할·번호로 테스트 계정 조회 */
    @Transactional(readOnly = true)
    public Optional<User> findTestAccount(Role role, int number) {
        return memberTestAccountUseCase.find(role, number);
    }

    /** 내 정보 수정 */
    @Transactional
    public User updateProfile(Long userId, String nickname) {
        return memberUpdateProfileUseCase.update(userId, nickname);
    }

    /** 회원 조회 */
    @Transactional(readOnly = true)
    public User findById(Long userId) {
        return memberSupport.findUser(userId);
    }
}
