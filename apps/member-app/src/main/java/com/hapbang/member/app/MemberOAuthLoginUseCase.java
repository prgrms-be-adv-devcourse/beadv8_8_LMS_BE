package com.hapbang.member.app;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
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

    /** 구글 이름 닉네임이 겹칠 때 숫자를 다시 뽑는 횟수 (#646). 다 겹치면 DUPLICATE_NICKNAME으로 가입을 멈춘다 */
    static final int SUFFIX_TRIES = 10;

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
        User user;
        try {
            // 저장하면서 바로 INSERT된다(ID 자동 생성). 확인과 저장 사이에 같은 닉네임이 먼저 저장되면 유일 인덱스에 걸린다
            user = userRepository.save(User.join(email, availableNickname(nickname)));
        } catch (DataIntegrityViolationException e) {
            if (MemberSupport.isNicknameViolation(e)) {
                throw new MemberException(MemberErrorCode.DUPLICATE_NICKNAME, e);
            }
            throw e;
        }
        oAuthAccountRepository.save(OAuthAccount.connect(user, provider, providerUserId));
        eventPublisher.publishEvent(new UserJoinedEvent(user.toDto()));
        return user;
    }

    /**
     * 구글 이름을 닉네임 규칙에 맞게 정리하고(이모지·특수문자·긴 채널명이 많다), 다른 회원이 쓰고 있으면 뒤에 숫자 4자리를 붙인다 (#646).
     * 숫자를 붙여도 겹치면 {@value #SUFFIX_TRIES}번까지 다시 뽑는다. 가입한 뒤 내 정보에서 바꿀 수 있다
     */
    private String availableNickname(String googleName) {
        String nickname = Nickname.fromGoogleName(googleName);
        if (!userRepository.existsByNicknameLower(nickname)) {
            return nickname;
        }
        for (int i = 0; i < SUFFIX_TRIES; i++) {
            String candidate = Nickname.withRandomSuffix(nickname);
            if (!userRepository.existsByNicknameLower(candidate)) {
                return candidate;
            }
        }
        throw new MemberException(MemberErrorCode.DUPLICATE_NICKNAME);
    }
}
