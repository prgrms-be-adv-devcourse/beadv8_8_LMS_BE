package com.hapbang.member.app;

import java.time.LocalDateTime;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.Password;
import com.hapbang.member.domain.PasswordCredential;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.PasswordCredentialRepository;

import lombok.RequiredArgsConstructor;

/**
 * 비밀번호 변경 (#499). 현재 비밀번호가 맞아야 바꾼다. 비밀번호로 가입하지 않은 회원(구글 회원)은 바꿀 수 없다.
 * refresh token 폐기·재발급은 Facade가 {@link MemberTokenUseCase}로 한다
 */
@Service
@RequiredArgsConstructor
public class MemberPasswordChangeUseCase {

    private final MemberSupport memberSupport;
    private final PasswordCredentialRepository passwordCredentialRepository;
    private final PasswordEncoder passwordEncoder;

    public User change(Long userId, String currentPassword, String newPassword) {
        User user = memberSupport.findActiveUser(userId);
        PasswordCredential credential = passwordCredentialRepository.findByUserId(userId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.PASSWORD_NOT_SET));
        if (!passwordEncoder.matches(currentPassword == null ? "" : currentPassword, credential.getPasswordHash())) {
            throw new MemberException(MemberErrorCode.INVALID_CURRENT_PASSWORD);
        }
        Password.validate(newPassword);
        credential.changePassword(passwordEncoder.encode(newPassword), LocalDateTime.now());
        return user;
    }
}
