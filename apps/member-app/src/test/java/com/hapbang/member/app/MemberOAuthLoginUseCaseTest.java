package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.OAuthAccount;
import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.OAuthAccountRepository;
import com.hapbang.member.out.PasswordCredentialRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserJoinedEvent;

@ExtendWith(MockitoExtension.class)
class MemberOAuthLoginUseCaseTest {

    @Mock
    UserRepository userRepository;

    @Mock
    OAuthAccountRepository oAuthAccountRepository;

    @Mock
    PasswordCredentialRepository passwordCredentialRepository;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    MemberOAuthLoginUseCase useCase;

    @Test
    @DisplayName("연결된 OAuth 계정이 있으면 기존 회원을 반환하고 가입하지 않는다")
    void login_existingUser() {
        User user = User.join("tester1@example.com", "tester1");
        given(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-1"))
                .willReturn(Optional.of(OAuthAccount.connect(user, OAuthProvider.GOOGLE, "google-sub-1")));

        User result = useCase.login(OAuthProvider.GOOGLE, "google-sub-1", "tester1@example.com", "tester1");

        assertThat(result).isSameAs(user);
        verify(userRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    @DisplayName("연결된 회원이 정지·탈퇴 상태면 LOGIN_NOT_ALLOWED (비밀번호 로그인과 같은 규칙, #593)")
    void login_notActive(UserStatus status) {
        User user = User.join("tester1@example.com", "tester1");
        ReflectionTestUtils.setField(user, "status", status);
        given(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-1"))
                .willReturn(Optional.of(OAuthAccount.connect(user, OAuthProvider.GOOGLE, "google-sub-1")));

        assertThatThrownBy(() -> useCase.login(OAuthProvider.GOOGLE, "google-sub-1", "tester1@example.com", "tester1"))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.LOGIN_NOT_ALLOWED);
    }

    @Test
    @DisplayName("연결된 OAuth 계정이 없으면 가입하고 OAuth 계정을 연결한 뒤 가입 이벤트를 발행한다")
    void login_newUser() {
        given(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-1"))
                .willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willAnswer(returnsFirstArg());

        User result = useCase.login(OAuthProvider.GOOGLE, "google-sub-1", "tester1@example.com", "tester1");

        assertThat(result.getEmail()).isEqualTo("tester1@example.com");
        assertThat(result.getNickname()).isEqualTo("tester1");

        ArgumentCaptor<OAuthAccount> account = ArgumentCaptor.forClass(OAuthAccount.class);
        verify(oAuthAccountRepository).save(account.capture());
        assertThat(account.getValue().getUser()).isSameAs(result);
        assertThat(account.getValue().getProviderUserId()).isEqualTo("google-sub-1");

        ArgumentCaptor<UserJoinedEvent> event = ArgumentCaptor.forClass(UserJoinedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().user().nickname()).isEqualTo("tester1");
    }

    @Test
    @DisplayName("비밀번호로 가입된 이메일로 처음 구글 로그인하면 EMAIL_REGISTERED_WITH_PASSWORD이고 가입하지 않는다 (#499)")
    void login_emailRegisteredWithPassword() {
        User existing = User.join("tester1@example.com", "tester1");
        given(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-1"))
                .willReturn(Optional.empty());
        given(userRepository.findByEmailIgnoreCase("tester1@example.com")).willReturn(Optional.of(existing));
        given(passwordCredentialRepository.existsByUserId(existing.getId())).willReturn(true);

        assertThatThrownBy(() -> useCase.login(OAuthProvider.GOOGLE, "google-sub-1", "tester1@example.com", "tester1"))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.EMAIL_REGISTERED_WITH_PASSWORD);
        verify(userRepository, never()).save(any());
        verify(oAuthAccountRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("비밀번호 정보가 없는 같은 이메일 회원이 있으면 DUPLICATE_EMAIL (DB 제약 오류 대신, #499)")
    void login_duplicateEmail() {
        User existing = User.join("tester1@example.com", "tester1");
        given(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-2"))
                .willReturn(Optional.empty());
        given(userRepository.findByEmailIgnoreCase("tester1@example.com")).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> useCase.login(OAuthProvider.GOOGLE, "google-sub-2", "tester1@example.com", "tester1"))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.DUPLICATE_EMAIL);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("처음 가입할 때 구글 이름을 닉네임 규칙에 맞게 정리해서 저장한다")
    void login_newUser_cleansGoogleName() {
        given(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-1"))
                .willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willAnswer(returnsFirstArg());

        User result = useCase.login(OAuthProvider.GOOGLE, "google-sub-1", "tester1@example.com", "🔥게임하는 민규🔥 [LIVE]");

        assertThat(result.getNickname()).isEqualTo("게임하는 민규 LIVE");
    }
}
