package com.hapbang.member.app;

import java.time.LocalDateTime;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.MemberEmail;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.Password;
import com.hapbang.member.domain.PasswordCredential;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.LoginAttemptStore;
import com.hapbang.member.out.PasswordCredentialRepository;
import com.hapbang.member.out.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 비밀번호 재설정 (#557). 비밀번호를 잊은 회원이 이메일 인증(재설정용 인증 토큰)을 마친 뒤 새 비밀번호를 정한다.
 * 인증 토큰 확인과 모든 기기 로그아웃은 Facade가 한다. 트랜잭션은 Facade가 연다.
 * <ul>
 *     <li>정상 회원만 (#557 결정). 토큰을 받은 뒤 탈퇴·정지됐으면 400 EMAIL_NOT_VERIFIED(재설정 코드도 정상 회원에게만 보낸다).</li>
 *     <li>비밀번호가 없으면(구글 회원, 마스킹으로 지운 비밀번호) 409 PASSWORD_NOT_SET. 재설정 코드는 비밀번호 회원에게만 가므로 정상 흐름에서는 생기지 않는다.</li>
 *     <li>새 비밀번호 규칙은 가입과 같다. 바꾸면 로그인 실패 잠금과 비밀번호 변경 실패 잠금을 모두 푼다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class MemberPasswordResetUseCase {

    private final UserRepository userRepository;
    private final PasswordCredentialRepository passwordCredentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptStore loginAttemptStore;

    /** 새 비밀번호로 바꾸고 그 회원을 돌려준다 */
    public User reset(String email, String newPassword) {
        String normalizedEmail = MemberEmail.normalize(email);
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .filter(User::isActive)
                .orElseThrow(() -> new MemberException(MemberErrorCode.EMAIL_NOT_VERIFIED));
        PasswordCredential credential = passwordCredentialRepository.findByUserId(user.getId())
                .filter(found -> !found.isWiped())
                .orElseThrow(() -> new MemberException(MemberErrorCode.PASSWORD_NOT_SET));
        Password.validate(newPassword);
        credential.changePassword(passwordEncoder.encode(newPassword), LocalDateTime.now());
        loginAttemptStore.unlock(normalizedEmail);
        loginAttemptStore.unlock(MemberPasswordChangeUseCase.attemptKey(user.getId()));
        return user;
    }
}
