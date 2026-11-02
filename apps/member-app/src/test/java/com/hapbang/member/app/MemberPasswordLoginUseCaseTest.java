package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.PasswordCredential;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.LoginAttemptStore;
import com.hapbang.member.out.PasswordCredentialRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

class MemberPasswordLoginUseCaseTest {

    /** 실제 BCrypt로 해시를 만들고 비교한다 */
    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private PasswordCredentialRepository passwordCredentialRepository;
    private LoginAttemptStore loginAttemptStore;
    private MemberPasswordLoginUseCase useCase;

    @BeforeEach
    void setUp() {
        passwordCredentialRepository = mock(PasswordCredentialRepository.class);
        loginAttemptStore = mock(LoginAttemptStore.class);
        useCase = new MemberPasswordLoginUseCase(passwordCredentialRepository, passwordEncoder, loginAttemptStore);
    }

    @Test
    @DisplayName("이메일·비밀번호가 맞으면 회원을 돌려주고 실패 횟수를 지운다 (이메일은 소문자로 맞춰 찾는다)")
    void login() {
        User user = registered("tester1@example.com", "abcd1234", UserStatus.ACTIVE);

        User result = useCase.login(" Tester1@Example.com ", "abcd1234");

        assertThat(result).isSameAs(user);
        verify(loginAttemptStore).reset("tester1@example.com");
        verify(loginAttemptStore, never()).recordFailure(anyString());
    }

    @Test
    @DisplayName("비밀번호가 틀리면 INVALID_CREDENTIALS이고 실패를 센다")
    void login_wrongPassword() {
        registered("tester1@example.com", "abcd1234", UserStatus.ACTIVE);

        assertError(() -> useCase.login("tester1@example.com", "wrong1234"), MemberErrorCode.INVALID_CREDENTIALS);
        verify(loginAttemptStore).recordFailure("tester1@example.com");
        verify(loginAttemptStore, never()).reset(anyString());
    }

    @Test
    @DisplayName("없는 이메일도 같은 INVALID_CREDENTIALS이고 똑같이 실패를 센다 (가입 여부를 알려주지 않는다)")
    void login_unknownEmail() {
        given(passwordCredentialRepository.findByUserEmail("none@example.com")).willReturn(Optional.empty());

        assertError(() -> useCase.login("none@example.com", "abcd1234"), MemberErrorCode.INVALID_CREDENTIALS);
        verify(loginAttemptStore).recordFailure("none@example.com");
    }

    @Test
    @DisplayName("잠긴 이메일이면 비밀번호를 확인하지 않고 LOGIN_LOCKED (맞는 비밀번호여도)")
    void login_locked() {
        registered("tester1@example.com", "abcd1234", UserStatus.ACTIVE);
        given(loginAttemptStore.isLocked("tester1@example.com")).willReturn(true);

        assertError(() -> useCase.login("tester1@example.com", "abcd1234"), MemberErrorCode.LOGIN_LOCKED);
        verify(passwordCredentialRepository, never()).findByUserEmail(any());
    }

    @Test
    @DisplayName("비밀번호가 맞아도 정지·탈퇴 회원이면 LOGIN_NOT_ALLOWED")
    void login_inactiveMember() {
        registered("tester1@example.com", "abcd1234", UserStatus.SUSPENDED);

        assertError(() -> useCase.login("tester1@example.com", "abcd1234"), MemberErrorCode.LOGIN_NOT_ALLOWED);
        verify(loginAttemptStore, never()).reset(anyString());
    }

    @Test
    @DisplayName("비밀번호가 비어 있어도 예외 없이 INVALID_CREDENTIALS")
    void login_nullPassword() {
        registered("tester1@example.com", "abcd1234", UserStatus.ACTIVE);

        assertError(() -> useCase.login("tester1@example.com", null), MemberErrorCode.INVALID_CREDENTIALS);
    }

    private User registered(String email, String password, UserStatus status) {
        User user = MemberFixture.member(Role.USER, status);
        given(passwordCredentialRepository.findByUserEmail(email)).willReturn(Optional.of(
                PasswordCredential.register(user, passwordEncoder.encode(password), LocalDateTime.now())));
        return user;
    }

    private static void assertError(ThrowingCallable call, MemberErrorCode errorCode) {
        assertThatThrownBy(call)
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }
}
