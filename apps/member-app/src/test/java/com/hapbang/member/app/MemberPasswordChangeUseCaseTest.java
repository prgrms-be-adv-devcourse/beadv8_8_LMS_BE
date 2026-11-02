package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.hapbang.member.out.LoginAttemptStore;
import com.hapbang.member.out.PasswordCredentialRepository;

class MemberPasswordChangeUseCaseTest {

    private static final Long USER_ID = 42L;
    private static final String KEY = "password-change:42";

    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private MemberSupport memberSupport;
    private PasswordCredentialRepository passwordCredentialRepository;
    private LoginAttemptStore loginAttemptStore;
    private MemberPasswordChangeUseCase useCase;
    private PasswordCredential credential;

    @BeforeEach
    void setUp() {
        memberSupport = mock(MemberSupport.class);
        passwordCredentialRepository = mock(PasswordCredentialRepository.class);
        loginAttemptStore = mock(LoginAttemptStore.class);
        useCase = new MemberPasswordChangeUseCase(memberSupport, passwordCredentialRepository, passwordEncoder,
                loginAttemptStore);
        User user = User.join("tester1@example.com", "tester1");
        credential = PasswordCredential.register(user, passwordEncoder.encode("abcd1234"), LocalDateTime.now());
        given(memberSupport.findActiveUser(USER_ID)).willReturn(user);
        given(passwordCredentialRepository.findByUserId(USER_ID)).willReturn(Optional.of(credential));
    }

    @Test
    @DisplayName("현재 비밀번호가 맞으면 새 해시로 바꾸고 실패 횟수를 지운다")
    void change() {
        useCase.change(USER_ID, "abcd1234", "newpass5678");

        assertThat(passwordEncoder.matches("newpass5678", credential.getPasswordHash())).isTrue();
        verify(loginAttemptStore).reset(KEY);
    }

    @Test
    @DisplayName("현재 비밀번호가 틀리면 INVALID_CURRENT_PASSWORD이고 실패를 센다 (로그인 잠금과 다른 키)")
    void change_wrongCurrentPassword() {
        assertError(() -> useCase.change(USER_ID, "wrong1234", "newpass5678"), MemberErrorCode.INVALID_CURRENT_PASSWORD);
        verify(loginAttemptStore).recordFailure(KEY);
        assertThat(passwordEncoder.matches("abcd1234", credential.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("잠겨 있으면 현재 비밀번호를 확인하지 않고 PASSWORD_CHANGE_LOCKED (맞는 비밀번호여도)")
    void change_locked() {
        given(loginAttemptStore.isLocked(KEY)).willReturn(true);

        assertError(() -> useCase.change(USER_ID, "abcd1234", "newpass5678"), MemberErrorCode.PASSWORD_CHANGE_LOCKED);
        verify(loginAttemptStore, never()).recordFailure(anyString());
        assertThat(passwordEncoder.matches("abcd1234", credential.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("비밀번호로 가입하지 않은 회원(구글 회원)은 PASSWORD_NOT_SET이고 실패를 세지 않는다")
    void change_passwordNotSet() {
        given(passwordCredentialRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

        assertError(() -> useCase.change(USER_ID, "abcd1234", "newpass5678"), MemberErrorCode.PASSWORD_NOT_SET);
        verify(loginAttemptStore, never()).recordFailure(anyString());
    }

    @Test
    @DisplayName("새 비밀번호가 규칙을 어기면 바꾸지 않는다")
    void change_invalidNewPassword() {
        assertError(() -> useCase.change(USER_ID, "abcd1234", "short"), MemberErrorCode.INVALID_PASSWORD_LENGTH);
        assertThat(passwordEncoder.matches("abcd1234", credential.getPasswordHash())).isTrue();
    }

    private static void assertError(ThrowingCallable call, MemberErrorCode errorCode) {
        assertThatThrownBy(call)
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }
}
