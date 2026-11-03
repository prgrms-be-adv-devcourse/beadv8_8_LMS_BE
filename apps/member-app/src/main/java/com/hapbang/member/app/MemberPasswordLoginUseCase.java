package com.hapbang.member.app;

import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.MemberEmail;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.PasswordCredential;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.LoginAttemptStore;
import com.hapbang.member.out.PasswordCredentialRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 이메일·비밀번호 로그인 (#499). 토큰 발급은 Facade가 {@link MemberTokenUseCase}로 한다.
 * <ul>
 *     <li>없는 이메일과 틀린 비밀번호는 같은 오류(INVALID_CREDENTIALS)로 답해 가입된 이메일인지 알려주지 않는다.
 *     없는 이메일도 가짜 해시로 같은 계산을 해서 응답 시간으로도 구별되지 않게 한다</li>
 *     <li>같은 이메일로 연속으로 틀리면 잠근다 (없는 이메일도 똑같이 센다). 성공하면 실패 횟수를 지운다</li>
 *     <li>비밀번호가 맞아도 정지·탈퇴 회원은 로그인할 수 없다. 복구 기한 안의 탈퇴 회원은 복구 티켓을 준다({@link MemberLoginGuard}, #593)</li>
 * </ul>
 */
@Slf4j
@Service
public class MemberPasswordLoginUseCase {

    private final PasswordCredentialRepository passwordCredentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptStore loginAttemptStore;
    private final MemberLoginGuard memberLoginGuard;
    /** 없는 이메일일 때 비교할 해시 (응답 시간을 맞추려고 실제 해시와 같은 비용으로 만든다) */
    private final String dummyHash;

    public MemberPasswordLoginUseCase(PasswordCredentialRepository passwordCredentialRepository,
                                      PasswordEncoder passwordEncoder, LoginAttemptStore loginAttemptStore,
                                      MemberLoginGuard memberLoginGuard) {
        this.passwordCredentialRepository = passwordCredentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptStore = loginAttemptStore;
        this.memberLoginGuard = memberLoginGuard;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing-1");
    }

    public User login(String email, String password) {
        String normalizedEmail = MemberEmail.normalize(email);
        if (loginAttemptStore.isLocked(normalizedEmail)) {
            throw new MemberException(MemberErrorCode.LOGIN_LOCKED);
        }
        Optional<PasswordCredential> credential = passwordCredentialRepository.findByUserEmail(normalizedEmail);
        String hash = credential.map(PasswordCredential::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(password == null ? "" : password, hash);
        if (credential.isEmpty() || !matches) {
            if (loginAttemptStore.recordFailure(normalizedEmail)) {
                log.warn("비밀번호 로그인 연속 실패로 잠금");
            }
            throw new MemberException(MemberErrorCode.INVALID_CREDENTIALS);
        }
        // 비밀번호가 맞았으니 실패 횟수는 지운다 (복구할까요?·정지로 끝나도 같다)
        loginAttemptStore.reset(normalizedEmail);
        User user = credential.get().getUser();
        memberLoginGuard.check(user);
        return user;
    }
}
