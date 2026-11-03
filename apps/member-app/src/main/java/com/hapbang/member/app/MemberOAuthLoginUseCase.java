package com.hapbang.member.app;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.Nickname;
import com.hapbang.member.domain.OAuthAccount;
import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.OAuthAccountRepository;
import com.hapbang.member.out.PasswordCredentialRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.event.UserJoinedEvent;

import lombok.RequiredArgsConstructor;

/** OAuth 로그인 (처음이면 가입) */
@Service
@RequiredArgsConstructor
public class MemberOAuthLoginUseCase {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oAuthAccountRepository;
    private final PasswordCredentialRepository passwordCredentialRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MemberLoginGuard memberLoginGuard;

    /**
     * 연결된 회원을 찾고, 없으면 가입시킨다. 비밀번호 로그인과 같은 규칙({@link MemberLoginGuard})으로 정지·탈퇴 회원은 로그인하지 않고,
     * 복구 기한 안의 탈퇴 회원은 복구 티켓을 준다 (#593). 막지 않으면 refresh token 쿠키를 발급한 뒤 재발급에서야 거절돼
     * 프론트가 로그인 성공 뒤 바로 만료를 보게 된다
     */
    public User login(OAuthProvider provider, String providerUserId, String email, String nickname) {
        User user = oAuthAccountRepository.findByProviderAndProviderUserId(provider, providerUserId)
                .map(OAuthAccount::getUser)
                .orElseGet(() -> join(provider, providerUserId, email, nickname));
        memberLoginGuard.check(user);
        return user;
    }

    /**
     * 신규 가입 + OAuth 계정 연결 + 가입 이벤트 발행.
     * 같은 이메일의 회원이 이미 있으면 가입하지 않는다 (#499, 대소문자 무시). 메일 인증이 없어 계정을 자동으로 잇지 않는다 (나중에 검토)
     */
    private User join(OAuthProvider provider, String providerUserId, String email, String nickname) {
        userRepository.findByEmailIgnoreCase(email).ifPresent(existing -> {
            throw new MemberException(passwordCredentialRepository.existsByUserId(existing.getId())
                    ? MemberErrorCode.EMAIL_REGISTERED_WITH_PASSWORD
                    : MemberErrorCode.DUPLICATE_EMAIL);
        });
        // 구글 이름은 이모지·특수문자·긴 채널명이 많아 닉네임 규칙에 맞게 정리한다
        User user = userRepository.save(User.join(email, Nickname.fromGoogleName(nickname)));
        oAuthAccountRepository.save(OAuthAccount.connect(user, provider, providerUserId));
        eventPublisher.publishEvent(new UserJoinedEvent(user.toDto()));
        return user;
    }
}
