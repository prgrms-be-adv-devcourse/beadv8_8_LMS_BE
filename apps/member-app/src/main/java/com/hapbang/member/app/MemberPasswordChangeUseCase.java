package com.hapbang.member.app;

import java.time.LocalDateTime;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.Password;
import com.hapbang.member.domain.PasswordCredential;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.LoginAttemptStore;
import com.hapbang.member.out.PasswordCredentialRepository;

import lombok.RequiredArgsConstructor;

/**
 * 비밀번호 변경 (#499). 현재 비밀번호가 맞아야 바꾼다. 비밀번호로 가입하지 않은 회원(구글 회원)은 바꿀 수 없다.
 * access token을 탈취당해도 현재 비밀번호를 마구 대입하지 못하게, 로그인과 같은 기준(5번 연속 실패 → 15분)으로 잠근다.
 * 로그인 잠금과는 따로 센다. refresh token 폐기·재발급은 Facade가 {@link MemberTokenUseCase}로 한다
 */
@Service
@RequiredArgsConstructor
public class MemberPasswordChangeUseCase {

    private final MemberSupport memberSupport;
    private final PasswordCredentialRepository passwordCredentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptStore loginAttemptStore;

    public User change(Long userId, String currentPassword, String newPassword) {
        User user = memberSupport.findActiveUser(userId);
        // 마스킹으로 지운 비밀번호(#594)는 없는 것과 같다 (탈퇴 회원이라 위에서 먼저 막히지만 비교기 오류를 피하려 한 번 더 거른다)
        PasswordCredential credential = passwordCredentialRepository.findByUserId(userId)
                .filter(found -> !found.isWiped())
                .orElseThrow(() -> new MemberException(MemberErrorCode.PASSWORD_NOT_SET));
        String attemptKey = attemptKey(userId);
        if (loginAttemptStore.isLocked(attemptKey)) {
            throw new MemberException(MemberErrorCode.PASSWORD_CHANGE_LOCKED);
        }
        if (!passwordEncoder.matches(currentPassword == null ? "" : currentPassword, credential.getPasswordHash())) {
            loginAttemptStore.recordFailure(attemptKey);
            throw new MemberException(MemberErrorCode.INVALID_CURRENT_PASSWORD);
        }
        loginAttemptStore.reset(attemptKey);
        Password.validate(newPassword);
        credential.changePassword(passwordEncoder.encode(newPassword), LocalDateTime.now());
        return user;
    }

    /** 로그인 잠금(이메일)과 겹치지 않는 비밀번호 변경용 키 */
    static String attemptKey(Long userId) {
        return "password-change:" + userId;
    }
}
