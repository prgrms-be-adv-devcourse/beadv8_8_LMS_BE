package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.PasswordCredential;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.PasswordCredentialRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserJoinedEvent;

class MemberSignUpUseCaseTest {

    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private UserRepository userRepository;
    private PasswordCredentialRepository passwordCredentialRepository;
    private ApplicationEventPublisher eventPublisher;
    private MemberSignUpUseCase useCase;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordCredentialRepository = mock(PasswordCredentialRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        useCase = new MemberSignUpUseCase(userRepository, passwordCredentialRepository, passwordEncoder, eventPublisher);
    }

    @Test
    @DisplayName("가입하면 유저·정상 회원과 비밀번호 해시를 저장하고 가입 이벤트를 발행한다 (이메일은 소문자로)")
    void signUp() {
        given(userRepository.saveAndFlush(any(User.class))).willAnswer(returnsFirstArg());

        User user = useCase.signUp(" Tester1@Example.com ", "abcd1234", "tester1");

        assertThat(user.getEmail()).isEqualTo("tester1@example.com");
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        ArgumentCaptor<PasswordCredential> credential = ArgumentCaptor.forClass(PasswordCredential.class);
        verify(passwordCredentialRepository).save(credential.capture());
        assertThat(credential.getValue().getUser()).isSameAs(user);
        assertThat(credential.getValue().getPasswordHash()).isNotEqualTo("abcd1234").startsWith("{bcrypt}");
        assertThat(passwordEncoder.matches("abcd1234", credential.getValue().getPasswordHash())).isTrue();
        verify(eventPublisher).publishEvent(any(UserJoinedEvent.class));
    }

    @Test
    @DisplayName("관리자 계정은 같은 규칙으로 만들고 역할만 관리자이며, 가입 이벤트에도 관리자로 나간다 (#517)")
    void registerAdmin() {
        given(userRepository.saveAndFlush(any(User.class))).willAnswer(returnsFirstArg());

        User user = useCase.registerAdmin("admin@example.com", "admin1234", "관리자");

        assertThat(user.getRole()).isEqualTo(Role.ADMIN);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(passwordCredentialRepository).save(any(PasswordCredential.class));
        ArgumentCaptor<UserJoinedEvent> event = ArgumentCaptor.forClass(UserJoinedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().user().role()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 DUPLICATE_EMAIL이고 저장하지 않는다")
    void signUp_duplicateEmail() {
        given(userRepository.existsByEmailIgnoreCase("tester1@example.com")).willReturn(true);

        assertError(() -> useCase.signUp("Tester1@example.com", "abcd1234", "tester1"), MemberErrorCode.DUPLICATE_EMAIL);
        verify(userRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("동시 가입으로 이메일 유일 제약에 걸리면 DUPLICATE_EMAIL")
    void signUp_concurrentDuplicate() {
        given(userRepository.saveAndFlush(any(User.class))).willThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"" + MemberSignUpUseCase.EMAIL_UNIQUE + "\""));

        assertError(() -> useCase.signUp("tester1@example.com", "abcd1234", "tester1"), MemberErrorCode.DUPLICATE_EMAIL);
        verify(passwordCredentialRepository, never()).save(any());
    }

    @Test
    @DisplayName("다른 회원이 쓰는 닉네임이면(대소문자 무시) DUPLICATE_NICKNAME이고 저장하지 않는다 (#646)")
    void signUp_duplicateNickname() {
        given(userRepository.existsByNicknameLower("Tester1")).willReturn(true);

        assertError(() -> useCase.signUp("tester1@example.com", "abcd1234", " Tester1 "),
                MemberErrorCode.DUPLICATE_NICKNAME);
        verify(userRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("동시 가입으로 닉네임 유일 인덱스에 걸리면 DUPLICATE_NICKNAME (#646)")
    void signUp_concurrentDuplicateNickname() {
        given(userRepository.saveAndFlush(any(User.class))).willThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"" + MemberSupport.NICKNAME_UNIQUE + "\""));

        assertError(() -> useCase.signUp("tester1@example.com", "abcd1234", "tester1"),
                MemberErrorCode.DUPLICATE_NICKNAME);
        verify(passwordCredentialRepository, never()).save(any());
    }

    @Test
    @DisplayName("다른 제약 위반은 그대로 던진다")
    void signUp_otherViolation() {
        given(userRepository.saveAndFlush(any(User.class))).willThrow(new DataIntegrityViolationException("other"));

        assertThatThrownBy(() -> useCase.signUp("tester1@example.com", "abcd1234", "tester1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("비밀번호·닉네임 규칙을 어기면 저장하지 않는다")
    void signUp_invalid() {
        assertError(() -> useCase.signUp("tester1@example.com", "short1", "tester1"),
                MemberErrorCode.INVALID_PASSWORD_LENGTH);
        assertError(() -> useCase.signUp("tester1@example.com", "abcd1234", "닉"),
                MemberErrorCode.INVALID_NICKNAME_LENGTH);
        verify(userRepository, never()).saveAndFlush(any());
    }

    private static void assertError(ThrowingCallable call, MemberErrorCode errorCode) {
        assertThatThrownBy(call)
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }
}
