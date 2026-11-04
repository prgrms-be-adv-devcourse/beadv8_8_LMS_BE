package com.hapbang.member.app;

import java.time.LocalDateTime;

import org.springframework.context.ApplicationEventPublisher;
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
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.event.UserJoinedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 이메일·비밀번호 가입 (#499). 구글 가입과 같이 유저·정상으로 가입하고 UserJoinedEvent를 발행한다.
 * 관리자가 만드는 관리자 계정(#517)과 역할별 초기 계정(#520)도 같은 규칙으로 만들고 역할만 다르다.
 * 이미 가입된 이메일(구글 회원 포함)이면 거절한다. 메일 인증이 없어 이메일 주인을 확인하지 않는다 (메일 발송은 나중에)
 * 다른 회원이 쓰는 닉네임(대소문자 무시, 탈퇴 회원 포함)이면 거절한다 (#646). 초기 계정도 같아, 겹치면 앱이 뜨지 않는다
 */
@Service
@RequiredArgsConstructor
public class MemberSignUpUseCase {

    private final UserRepository userRepository;
    private final PasswordCredentialRepository passwordCredentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    /** 회원 가입 (역할은 유저) */
    public User signUp(String email, String password, String nickname) {
        return register(email, password, nickname, Role.USER);
    }

    /** 관리자 계정 생성 (#517). 권한 확인은 관리자 Facade가 한다 */
    public User registerAdmin(String email, String password, String nickname) {
        return register(email, password, nickname, Role.ADMIN);
    }

    /** 역할을 정해 계정을 만든다 (초기 계정, #520). 규칙·해시 저장·가입 이벤트는 가입과 같다 */
    public User register(String email, String password, String nickname, Role role) {
        String normalizedEmail = MemberEmail.normalize(email);
        Password.validate(password);
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new MemberException(MemberErrorCode.DUPLICATE_EMAIL);
        }
        // 닉네임 규칙은 User.join이 검사한다 (해시 계산 전에 실패하게 먼저 만든다)
        User user = User.join(normalizedEmail, nickname);
        if (userRepository.existsByNicknameLower(user.getNickname())) {
            throw new MemberException(MemberErrorCode.DUPLICATE_NICKNAME);
        }
        if (role != Role.USER) {
            user.changeRole(role);
        }
        try {
            // 바로 INSERT해서 동시 가입의 유일 제약 위반을 여기서 409로 바꾼다
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw UserUniqueViolation.translate(e);
        }
        passwordCredentialRepository.save(
                PasswordCredential.register(user, passwordEncoder.encode(password), LocalDateTime.now()));
        eventPublisher.publishEvent(new UserJoinedEvent(user.toDto()));
        return user;
    }
}
