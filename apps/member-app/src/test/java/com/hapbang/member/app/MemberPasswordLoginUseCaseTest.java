package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.hapbang.member.domain.ClientIpKey;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.PasswordCredential;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.global.ratelimit.RateLimitExceededException;
import com.hapbang.member.out.PasswordCredentialRepository;
import com.hapbang.member.out.PasswordLoginLockStore;
import com.hapbang.member.out.PasswordLoginLockStore.FailureResult;
import com.hapbang.member.out.PasswordLoginLockStore.Lock;
import com.hapbang.member.out.RestoreTicketStore;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MemberPasswordLoginUseCaseTest {

    private static final String IP = "203.0.113.10";
    private static final ClientIpKey IP_KEY = ClientIpKey.from(IP).orElseThrow();
    private static final String EMAIL = "tester1@example.com";

    /** 실제 BCrypt로 해시를 만들고 비교한다 */
    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private PasswordCredentialRepository passwordCredentialRepository;
    private PasswordLoginLockStore lockStore;
    private LoginRateLimit loginRateLimit;
    private SimpleMeterRegistry registry;
    private MemberPasswordLoginUseCase useCase;

    @BeforeEach
    void setUp() {
        passwordCredentialRepository = mock(PasswordCredentialRepository.class);
        lockStore = mock(PasswordLoginLockStore.class);
        given(lockStore.check(anyString(), any())).willReturn(Lock.NONE);
        given(lockStore.recordFailure(anyString(), any())).willReturn(new FailureResult(false, false));
        loginRateLimit = mock(LoginRateLimit.class);
        registry = new SimpleMeterRegistry();
        // 탈퇴 기능이 꺼진 판단(복구 티켓 없음)으로 둔다. 복구 티켓 판단은 MemberLoginGuardTest가 확인한다
        @SuppressWarnings("unchecked")
        ObjectProvider<MemberWithdrawalUseCase> withdrawalDisabled = mock(ObjectProvider.class);
        MemberLoginGuard memberLoginGuard = new MemberLoginGuard(withdrawalDisabled, mock(RestoreTicketStore.class));
        useCase = new MemberPasswordLoginUseCase(passwordCredentialRepository, passwordEncoder, lockStore,
                memberLoginGuard, loginRateLimit, registry);
    }

    @Test
    @DisplayName("IP 한도를 넘으면 잠금·비밀번호를 확인하지 않고 429로 거절한다 (실패 횟수도 세지 않는다)")
    void rateLimited() {
        willThrow(new RateLimitExceededException(30)).given(loginRateLimit).acquire(IP);

        assertThatThrownBy(() -> useCase.login(EMAIL, "abcd1234", IP))
                .isInstanceOf(RateLimitExceededException.class);
        verify(passwordCredentialRepository, never()).findByUserEmail(anyString());
        verify(lockStore, never()).recordFailure(anyString(), any());
    }

    @Test
    @DisplayName("이메일·비밀번호가 맞으면 회원을 돌려주고 이 IP의 성공을 남긴다 (이메일은 소문자로 맞춰 찾는다)")
    void login() {
        User user = registered(EMAIL, "abcd1234", UserStatus.ACTIVE);

        User result = useCase.login(" Tester1@Example.com ", "abcd1234", IP);

        assertThat(result).isSameAs(user);
        verify(lockStore).check(EMAIL, IP_KEY);
        verify(lockStore).recordSuccess(EMAIL, IP_KEY);
        verify(lockStore, never()).recordFailure(anyString(), any());
    }

    @Test
    @DisplayName("비밀번호가 틀리면 INVALID_CREDENTIALS이고 (이메일, IP)로 실패를 센다")
    void login_wrongPassword() {
        registered(EMAIL, "abcd1234", UserStatus.ACTIVE);

        assertError(() -> useCase.login(EMAIL, "wrong1234", IP), MemberErrorCode.INVALID_CREDENTIALS);
        verify(lockStore).recordFailure(EMAIL, IP_KEY);
        verify(lockStore, never()).recordSuccess(anyString(), any());
    }

    @Test
    @DisplayName("없는 이메일도 같은 INVALID_CREDENTIALS이고 똑같이 실패를 센다 (가입 여부를 알려주지 않는다)")
    void login_unknownEmail() {
        given(passwordCredentialRepository.findByUserEmail("none@example.com")).willReturn(Optional.empty());

        assertError(() -> useCase.login("none@example.com", "abcd1234", IP), MemberErrorCode.INVALID_CREDENTIALS);
        verify(lockStore).recordFailure("none@example.com", IP_KEY);
    }

    @Test
    @DisplayName("공인 주소가 아니면 IP를 모르는 것(null)으로 확인·실패·성공을 넘긴다")
    void login_privateAddress() {
        registered(EMAIL, "abcd1234", UserStatus.ACTIVE);

        assertError(() -> useCase.login(EMAIL, "wrong1234", "10.42.0.15"), MemberErrorCode.INVALID_CREDENTIALS);
        useCase.login(EMAIL, "abcd1234", "10.42.0.15");

        verify(lockStore).recordFailure(eq(EMAIL), isNull());
        verify(lockStore).recordSuccess(eq(EMAIL), isNull());
    }

    @Test
    @DisplayName("(이메일, IP)·이메일 전체 어느 잠금이든 비밀번호를 확인하지 않고 LOGIN_LOCKED (맞는 비밀번호여도)")
    void login_locked() {
        registered(EMAIL, "abcd1234", UserStatus.ACTIVE);

        given(lockStore.check(EMAIL, IP_KEY)).willReturn(Lock.EMAIL_IP);
        assertError(() -> useCase.login(EMAIL, "abcd1234", IP), MemberErrorCode.LOGIN_LOCKED);
        given(lockStore.check(EMAIL, IP_KEY)).willReturn(Lock.EMAIL);
        assertError(() -> useCase.login(EMAIL, "abcd1234", IP), MemberErrorCode.LOGIN_LOCKED);

        verify(passwordCredentialRepository, never()).findByUserEmail(any());
    }

    @Test
    @DisplayName("이번 실패로 잠기면 잠금 종류별로 지표를 센다")
    void login_lockMetrics() {
        registered(EMAIL, "abcd1234", UserStatus.ACTIVE);
        given(lockStore.recordFailure(EMAIL, IP_KEY)).willReturn(new FailureResult(true, true));

        assertError(() -> useCase.login(EMAIL, "wrong1234", IP), MemberErrorCode.INVALID_CREDENTIALS);

        assertThat(lockedCount("email_ip")).isEqualTo(1);
        assertThat(lockedCount("email")).isEqualTo(1);
    }

    @Test
    @DisplayName("비밀번호가 맞아도 정지·탈퇴 회원이면 LOGIN_NOT_ALLOWED이고, 비밀번호는 맞았으니 성공을 남긴다")
    void login_inactiveMember() {
        registered(EMAIL, "abcd1234", UserStatus.SUSPENDED);

        assertError(() -> useCase.login(EMAIL, "abcd1234", IP), MemberErrorCode.LOGIN_NOT_ALLOWED);
        verify(lockStore).recordSuccess(EMAIL, IP_KEY);
    }

    @Test
    @DisplayName("비밀번호가 비어 있어도 예외 없이 INVALID_CREDENTIALS")
    void login_nullPassword() {
        registered(EMAIL, "abcd1234", UserStatus.ACTIVE);

        assertError(() -> useCase.login(EMAIL, null, IP), MemberErrorCode.INVALID_CREDENTIALS);
    }

    private double lockedCount(String scope) {
        return registry.get(MemberPasswordLoginUseCase.LOCKED_METRIC).tag("scope", scope).counter().count();
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
