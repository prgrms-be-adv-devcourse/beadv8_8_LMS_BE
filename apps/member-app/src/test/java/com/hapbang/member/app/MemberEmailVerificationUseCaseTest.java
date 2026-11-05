package com.hapbang.member.app;

import static com.hapbang.member.domain.EmailVerificationPurpose.PASSWORD_RESET;
import static com.hapbang.member.domain.EmailVerificationPurpose.SIGN_UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.member.domain.EmailVerificationPolicy;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.PasswordCredential;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.EmailSender;
import com.hapbang.member.out.EmailVerificationStore;
import com.hapbang.member.out.PasswordCredentialRepository;
import com.hapbang.member.out.UserRepository;

/** 가입 인증 코드 보내기 (#557) */
@ExtendWith(MockitoExtension.class)
class MemberEmailVerificationUseCaseTest {

    private static final String PUBLIC_IP = "203.0.113.5";

    @Mock
    UserRepository userRepository;

    @Mock
    EmailVerificationStore store;

    @Mock
    EmailSender emailSender;

    @Mock
    PasswordCredentialRepository passwordCredentialRepository;

    /** 메일 대기열 대신 그 자리에서 바로 실행한다(뒤에서 보내는 동작을 순서대로 확인하려고) */
    MemberEmailVerificationUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new MemberEmailVerificationUseCase(userRepository, passwordCredentialRepository, store, emailSender,
                Runnable::run);
    }

    @Test
    @DisplayName("소문자로 바꾼 이메일로 6자리 코드를 저장하고 그 코드를 메일로 보낸다")
    void sendSignUpCode() {
        allowAll("tester@example.com", PUBLIC_IP);

        useCase.sendCode(" Tester@Example.com ", SIGN_UP, PUBLIC_IP);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(store).saveCode(eq("tester@example.com"), eq(SIGN_UP), code.capture());
        assertThat(code.getValue()).matches("\\d{6}");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq("tester@example.com"), eq(MemberEmailVerificationUseCase.SUBJECT), body.capture());
        assertThat(body.getValue()).contains(code.getValue(), "10분");
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 오류 없이(같은 응답) 코드 대신 \"이미 가입됨\" 안내 메일을 보내고 코드는 저장하지 않는다. 제한은 똑같이 센다")
    void registeredEmailGetsNotice() {
        allowAll("tester@example.com", PUBLIC_IP);
        given(userRepository.existsByEmailIgnoreCase("tester@example.com")).willReturn(true);

        useCase.sendCode("tester@example.com", SIGN_UP, PUBLIC_IP);

        verify(emailSender).send(eq("tester@example.com"), eq(MemberEmailVerificationUseCase.ALREADY_REGISTERED_SUBJECT),
                anyString());
        verify(store, never()).saveCode(anyString(), any(), anyString());
        verify(store).tryCountIp(PUBLIC_IP);
        verify(store).tryCountEmailDaily("tester@example.com");
        verify(store).tryCountGlobalDaily();
    }

    @Test
    @DisplayName("1분 간격 안이면 429 EMAIL_CODE_RESEND_TOO_SOON이고, IP·이메일·전체 수를 쓰지 않는다 (연타해도 한도가 닳지 않게)")
    void resendTooSoon() {
        given(store.tryStartResendCooldown("tester@example.com")).willReturn(false);

        assertError(() -> useCase.sendCode("tester@example.com", SIGN_UP, PUBLIC_IP),
                MemberErrorCode.EMAIL_CODE_RESEND_TOO_SOON);
        verify(store, never()).tryCountIp(anyString());
        verify(store, never()).tryCountEmailDaily(anyString());
        verify(store, never()).tryCountGlobalDaily();
        verify(store, never()).clearResendCooldown(anyString());
    }

    @Test
    @DisplayName("IP 한도를 넘으면 429 EMAIL_CODE_TOO_MANY_REQUESTS, 1분 간격은 되돌리고 보내지 않는다")
    void ipLimitExceeded() {
        given(store.tryStartResendCooldown("tester@example.com")).willReturn(true);
        given(store.tryCountIp(PUBLIC_IP)).willReturn(false);

        assertError(() -> useCase.sendCode("tester@example.com", SIGN_UP, PUBLIC_IP),
                MemberErrorCode.EMAIL_CODE_TOO_MANY_REQUESTS);
        verify(store).clearResendCooldown("tester@example.com");
        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("이메일 하루 한도를 넘으면 429 EMAIL_CODE_DAILY_LIMIT, 1분 간격은 되돌리고 보내지 않는다")
    void emailDailyLimitExceeded() {
        given(store.tryStartResendCooldown("tester@example.com")).willReturn(true);
        given(store.tryCountIp(PUBLIC_IP)).willReturn(true);
        given(store.tryCountEmailDaily("tester@example.com")).willReturn(false);

        assertError(() -> useCase.sendCode("tester@example.com", SIGN_UP, PUBLIC_IP), MemberErrorCode.EMAIL_CODE_DAILY_LIMIT);
        verify(store).clearResendCooldown("tester@example.com");
        verify(store, never()).tryCountGlobalDaily();
        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("앱 전체 하루 상한에 닿으면 503 EMAIL_SEND_UNAVAILABLE, 1분 간격은 되돌리고 보내지 않는다")
    void globalDailyLimitReached() {
        given(store.tryStartResendCooldown("tester@example.com")).willReturn(true);
        given(store.tryCountIp(PUBLIC_IP)).willReturn(true);
        given(store.tryCountEmailDaily("tester@example.com")).willReturn(true);
        given(store.tryCountGlobalDaily()).willReturn(false);

        assertError(() -> useCase.sendCode("tester@example.com", SIGN_UP, PUBLIC_IP), MemberErrorCode.EMAIL_SEND_UNAVAILABLE);
        verify(store).clearResendCooldown("tester@example.com");
        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("IP가 내부 주소면 IP 제한만 건너뛰고(이메일·전체 한도는 센다) 보낸다")
    void privateIpSkipsLimit() {
        given(store.tryStartResendCooldown("tester@example.com")).willReturn(true);
        given(store.tryCountEmailDaily("tester@example.com")).willReturn(true);
        given(store.tryCountGlobalDaily()).willReturn(true);

        useCase.sendCode("tester@example.com", SIGN_UP, "10.42.0.15");

        verify(store, never()).tryCountIp(anyString());
        verify(emailSender).send(eq("tester@example.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("뒤에서 메일을 못 보내면 요청은 그대로 성공이고, 코드와 1분 간격을 지워 바로 다시 받을 수 있게 한다")
    void sendFailedInBackground() {
        allowAll("tester@example.com", PUBLIC_IP);
        willThrow(new MailSendException("smtp down")).given(emailSender).send(anyString(), anyString(), anyString());

        given(store.deleteCodeIfMatches(eq("tester@example.com"), eq(SIGN_UP), anyString())).willReturn(true);

        useCase.sendCode("tester@example.com", SIGN_UP, PUBLIC_IP);

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(store).saveCode(eq("tester@example.com"), eq(SIGN_UP), saved.capture());
        verify(store).deleteCodeIfMatches("tester@example.com", SIGN_UP, saved.getValue());
        verify(store).clearResendCooldown("tester@example.com");
    }

    @Test
    @DisplayName("늦게 실패한 메일은 그 사이 같은 이메일이 새로 받은 코드와 1분 간격을 지우지 않는다 (이번 코드일 때만 지움)")
    void lateFailureKeepsNewerCode() {
        List<Runnable> queued = new ArrayList<>();
        useCase = new MemberEmailVerificationUseCase(userRepository, passwordCredentialRepository, store, emailSender,
                queued::add);
        allowAll("tester@example.com", PUBLIC_IP);
        AtomicReference<String> latest = new AtomicReference<>();
        willAnswer(invocation -> {
            latest.set(invocation.getArgument(2));
            return null;
        }).given(store).saveCode(eq("tester@example.com"), eq(SIGN_UP), anyString());
        given(store.deleteCodeIfMatches(eq("tester@example.com"), eq(SIGN_UP), anyString()))
                .willAnswer(invocation -> invocation.getArgument(2).equals(latest.get()));
        willThrow(new MailSendException("smtp down")).given(emailSender).send(anyString(), anyString(), anyString());

        useCase.sendCode("tester@example.com", SIGN_UP, PUBLIC_IP); // A: 대기열에서 밀림
        useCase.sendCode("tester@example.com", SIGN_UP, PUBLIC_IP); // B: 1분 뒤 새 코드
        queued.get(0).run(); // A가 뒤늦게 실패

        verify(store, never()).clearResendCooldown(anyString());
        assertThat(latest.get()).isNotNull();
    }

    @Test
    @DisplayName("안내 메일(코드 없음)을 못 보내면 1분 간격만 지운다")
    void noticeFailureClearsCooldownOnly() {
        allowAll("tester@example.com", PUBLIC_IP);
        given(userRepository.existsByEmailIgnoreCase("tester@example.com")).willReturn(true);
        willThrow(new MailSendException("smtp down")).given(emailSender).send(anyString(), anyString(), anyString());

        useCase.sendCode("tester@example.com", SIGN_UP, PUBLIC_IP);

        verify(store, never()).deleteCodeIfMatches(anyString(), any(), anyString());
        verify(store).clearResendCooldown("tester@example.com");
    }

    @Test
    @DisplayName("메일 대기열이 가득 차면 503 EMAIL_SEND_UNAVAILABLE이고 이번 코드와 1분 간격을 지운다")
    void queueFull() {
        allowAll("tester@example.com", PUBLIC_IP);
        useCase = new MemberEmailVerificationUseCase(userRepository, passwordCredentialRepository, store, emailSender,
                task -> {
                    throw new RejectedExecutionException("full");
                });

        assertError(() -> useCase.sendCode("tester@example.com", SIGN_UP, PUBLIC_IP), MemberErrorCode.EMAIL_SEND_UNAVAILABLE);
        verify(store).deleteCodeIfMatches(eq("tester@example.com"), eq(SIGN_UP), anyString());
        verify(store).clearResendCooldown("tester@example.com");
        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("제한에 막히면 이전에 받은 유효한 코드는 지우지 않는다 (1분 간격만 되돌림)")
    void limitKeepsPreviousCode() {
        given(store.tryStartResendCooldown("tester@example.com")).willReturn(true);
        given(store.tryCountIp(PUBLIC_IP)).willReturn(true);
        given(store.tryCountEmailDaily("tester@example.com")).willReturn(false);

        assertError(() -> useCase.sendCode("tester@example.com", SIGN_UP, PUBLIC_IP), MemberErrorCode.EMAIL_CODE_DAILY_LIMIT);
        verify(store, never()).deleteCodeIfMatches(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("제한을 세다 Redis 오류가 나도 1분 간격을 되돌리고 원래 오류를 그대로 던진다")
    void redisErrorClearsCooldown() {
        given(store.tryStartResendCooldown("tester@example.com")).willReturn(true);
        IllegalStateException redisDown = new IllegalStateException("redis down");
        given(store.tryCountIp(PUBLIC_IP)).willThrow(redisDown);

        assertThatThrownBy(() -> useCase.sendCode("tester@example.com", SIGN_UP, PUBLIC_IP)).isSameAs(redisDown);
        verify(store).clearResendCooldown("tester@example.com");
    }

    @Test
    @DisplayName("재설정: 비밀번호가 있는 정상 회원이면 재설정 코드를 저장하고 메일로 보낸다")
    void passwordResetCode() {
        allowAll("tester@example.com", PUBLIC_IP);
        User user = activeUser(7L);
        given(userRepository.findByEmailIgnoreCase("tester@example.com")).willReturn(Optional.of(user));
        given(passwordCredentialRepository.findByUserId(7L))
                .willReturn(Optional.of(PasswordCredential.register(user, "{bcrypt}hash", LocalDateTime.now())));

        useCase.sendCode("tester@example.com", PASSWORD_RESET, PUBLIC_IP);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(store).saveCode(eq("tester@example.com"), eq(PASSWORD_RESET), code.capture());
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq("tester@example.com"), eq(MemberEmailVerificationUseCase.PASSWORD_RESET_SUBJECT),
                body.capture());
        assertThat(body.getValue()).contains(code.getValue());
    }

    @Test
    @DisplayName("재설정: 구글로만 가입한(비밀번호 없는) 정상 회원이면 코드 대신 구글 로그인 안내를 보낸다")
    void passwordResetGoogleMember() {
        allowAll("tester@example.com", PUBLIC_IP);
        given(userRepository.findByEmailIgnoreCase("tester@example.com")).willReturn(Optional.of(activeUser(7L)));
        given(passwordCredentialRepository.findByUserId(7L)).willReturn(Optional.empty());

        useCase.sendCode("tester@example.com", PASSWORD_RESET, PUBLIC_IP);

        verify(emailSender).send(eq("tester@example.com"), eq(MemberEmailVerificationUseCase.GOOGLE_ACCOUNT_SUBJECT),
                anyString());
        verify(store, never()).saveCode(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("재설정: 없는 아이디·탈퇴·정지 회원이면 아무것도 보내지 않지만 같은 응답(예외 없음)이고 제한은 센다")
    void passwordResetNoMember() {
        allowAll("none@example.com", PUBLIC_IP);
        allowAll("withdrawn@example.com", PUBLIC_IP);
        User withdrawn = activeUser(8L);
        withdrawn.withdraw(LocalDateTime.now());
        given(userRepository.findByEmailIgnoreCase("none@example.com")).willReturn(Optional.empty());
        given(userRepository.findByEmailIgnoreCase("withdrawn@example.com")).willReturn(Optional.of(withdrawn));

        useCase.sendCode("none@example.com", PASSWORD_RESET, PUBLIC_IP);
        useCase.sendCode("withdrawn@example.com", PASSWORD_RESET, PUBLIC_IP);

        verify(emailSender, never()).send(anyString(), anyString(), anyString());
        verify(store).tryCountEmailDaily("none@example.com");
        verify(store).tryCountEmailDaily("withdrawn@example.com");
    }

    @Test
    @DisplayName("코드가 맞으면 소문자 이메일로 인증 토큰을 발급한다")
    void verifyMatched() {
        given(store.checkCode("tester@example.com", SIGN_UP, "123456", EmailVerificationPolicy.MAX_CODE_ATTEMPTS))
                .willReturn(EmailVerificationStore.CodeCheck.MATCHED);
        given(store.issueToken("tester@example.com", SIGN_UP)).willReturn("token-1");

        assertThat(useCase.verifyCode("Tester@example.com", SIGN_UP, "123456")).isEqualTo("token-1");
    }

    @Test
    @DisplayName("틀리면 INVALID_EMAIL_CODE, 횟수 초과·코드 없음은 EMAIL_CODE_EXPIRED이고 토큰을 발급하지 않는다")
    void verifyRejected() {
        given(store.checkCode(eq("tester@example.com"), eq(SIGN_UP), anyString(), eq(EmailVerificationPolicy.MAX_CODE_ATTEMPTS)))
                .willReturn(EmailVerificationStore.CodeCheck.WRONG, EmailVerificationStore.CodeCheck.ATTEMPTS_EXCEEDED,
                        EmailVerificationStore.CodeCheck.NOT_FOUND);

        assertError(() -> useCase.verifyCode("tester@example.com", SIGN_UP, "000000"), MemberErrorCode.INVALID_EMAIL_CODE);
        assertError(() -> useCase.verifyCode("tester@example.com", SIGN_UP, "000000"), MemberErrorCode.EMAIL_CODE_EXPIRED);
        assertError(() -> useCase.verifyCode("tester@example.com", SIGN_UP, "000000"), MemberErrorCode.EMAIL_CODE_EXPIRED);
        verify(store, never()).issueToken(anyString(), any());
    }

    @Test
    @DisplayName("가입 뒤 인증 토큰을 지우다 Redis 오류가 나도 예외를 던지지 않는다 (가입은 이미 끝났으므로 500으로 만들지 않음)")
    void consumeTokenFailureIsQuiet() {
        willThrow(new IllegalStateException("redis down")).given(store).deleteToken("token-1", SIGN_UP);

        useCase.consumeAfterUse("token-1", SIGN_UP);

        verify(store).deleteToken("token-1", SIGN_UP);
    }

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.5", "8.8.8.8", "2001:4860:4860::8888"})
    @DisplayName("인터넷 주소는 공인 주소로 본다")
    void publicAddresses(String ip) {
        assertThat(MemberEmailVerificationUseCase.isPublicAddress(ip)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.42.0.15", "172.16.0.1", "192.168.1.10", "127.0.0.1", "169.254.169.254", "::1",
            "fd00::1", "fe80::1", "0.0.0.0", "", "localhost", "evil.example.com", "dead.beef"})
    @DisplayName("사설·루프백·링크 로컬·빈 값·이름은 공인 주소로 보지 않는다(이름은 DNS 조회도 하지 않는다)")
    void nonPublicAddresses(String ip) {
        assertThat(MemberEmailVerificationUseCase.isPublicAddress(ip)).isFalse();
    }

    @Test
    @DisplayName("IP 제한 단위: IPv4는 주소 그대로, IPv6는 같은 /64면 같은 것으로 센다")
    void ipLimitKey() {
        assertThat(MemberEmailVerificationUseCase.ipLimitKey("203.0.113.5")).isEqualTo("203.0.113.5");
        assertThat(MemberEmailVerificationUseCase.ipLimitKey("2001:db8:1:2::1"))
                .isEqualTo(MemberEmailVerificationUseCase.ipLimitKey("2001:db8:1:2:ffff:eeee:dddd:cccc"))
                .isEqualTo("v6/64:2001:0db8:0001:0002");
        assertThat(MemberEmailVerificationUseCase.ipLimitKey("2001:db8:1:3::1"))
                .isNotEqualTo(MemberEmailVerificationUseCase.ipLimitKey("2001:db8:1:2::1"));
    }

    @Test
    @DisplayName("코드는 앞자리 0을 포함한 6자리 숫자다")
    void newCode() {
        for (int i = 0; i < 100; i++) {
            assertThat(MemberEmailVerificationUseCase.newCode()).matches("\\d{6}");
        }
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, MemberErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(MemberException.class).extracting("errorCode").isEqualTo(code);
    }

    /** 1분 간격·IP·이메일·전체 한도를 모두 통과시킨다 */
    private void allowAll(String email, String ip) {
        given(store.tryStartResendCooldown(email)).willReturn(true);
        given(store.tryCountIp(ip)).willReturn(true);
        given(store.tryCountEmailDaily(email)).willReturn(true);
        given(store.tryCountGlobalDaily()).willReturn(true);
    }

    private static User activeUser(Long id) {
        User user = User.join("tester@example.com", "tester");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
