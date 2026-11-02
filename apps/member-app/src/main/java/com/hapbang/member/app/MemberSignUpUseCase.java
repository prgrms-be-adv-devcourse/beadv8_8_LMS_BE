package com.hapbang.member.app;

import java.time.LocalDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.MemberEmail;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.Password;
import com.hapbang.member.domain.PasswordCredential;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.PasswordCredentialRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.event.UserJoinedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 이메일·비밀번호 가입 (#499). 구글 가입과 같이 유저·정상으로 가입하고 UserJoinedEvent를 발행한다.
 * 이미 가입된 이메일(구글 회원 포함)이면 거절한다. 메일 인증이 없어 이메일 주인을 확인하지 않는다 (메일 발송은 나중에)
 */
@Service
@RequiredArgsConstructor
public class MemberSignUpUseCase {

    /** users.email 유일 제약 (V202610121119) */
    static final String EMAIL_UNIQUE = "uc_users_email";

    private final UserRepository userRepository;
    private final PasswordCredentialRepository passwordCredentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public User signUp(String email, String password, String nickname) {
        String normalizedEmail = MemberEmail.normalize(email);
        Password.validate(password);
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new MemberException(MemberErrorCode.DUPLICATE_EMAIL);
        }
        // 닉네임 규칙은 User.join이 검사한다 (해시 계산 전에 실패하게 먼저 만든다)
        User user = User.join(normalizedEmail, nickname);
        try {
            // 바로 INSERT해서 동시 가입의 유일 제약 위반을 여기서 409로 바꾼다
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            if (isEmailViolation(e)) {
                throw new MemberException(MemberErrorCode.DUPLICATE_EMAIL, e);
            }
            throw e;
        }
        passwordCredentialRepository.save(
                PasswordCredential.register(user, passwordEncoder.encode(password), LocalDateTime.now()));
        eventPublisher.publishEvent(new UserJoinedEvent(user.toDto()));
        return user;
    }

    private static boolean isEmailViolation(DataIntegrityViolationException e) {
        String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return message != null && message.contains(EMAIL_UNIQUE);
    }
}
