package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;

import com.hapbang.member.domain.EmailVerificationPolicy;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.out.EmailSender;
import com.hapbang.member.out.EmailVerificationStore;
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

    @InjectMocks
    MemberEmailVerificationUseCase useCase;

    @Test
    @DisplayName("소문자로 바꾼 이메일로 6자리 코드를 저장하고 그 코드를 메일로 보낸다")
    void sendSignUpCode() {
        allowAll("tester@example.com", PUBLIC_IP);

        useCase.sendSignUpCode(" Tester@Example.com ", PUBLIC_IP);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(store).saveCode(eq("tester@example.com"), code.capture());
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

        useCase.sendSignUpCode("tester@example.com", PUBLIC_IP);

        verify(emailSender).send(eq("tester@example.com"), eq(MemberEmailVerificationUseCase.ALREADY_REGISTERED_SUBJECT),
                anyString());
        verify(store, never()).saveCode(anyString(), anyString());
        verify(store).tryCountIp(PUBLIC_IP);
        verify(store).tryCountEmailDaily("tester@example.com");
        verify(store).tryCountGlobalDaily();
    }

    @Test
    @DisplayName("1분 간격 안이면 429 EMAIL_CODE_RESEND_TOO_SOON이고, IP·이메일·전체 수를 쓰지 않는다 (연타해도 한도가 닳지 않게)")
    void resendTooSoon() {
        given(store.tryStartResendCooldown("tester@example.com")).willReturn(false);

        assertError(() -> useCase.sendSignUpCode("tester@example.com", PUBLIC_IP),
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

        assertError(() -> useCase.sendSignUpCode("tester@example.com", PUBLIC_IP),
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

        assertError(() -> useCase.sendSignUpCode("tester@example.com", PUBLIC_IP), MemberErrorCode.EMAIL_CODE_DAILY_LIMIT);
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

        assertError(() -> useCase.sendSignUpCode("tester@example.com", PUBLIC_IP), MemberErrorCode.EMAIL_SEND_UNAVAILABLE);
        verify(store).clearResendCooldown("tester@example.com");
        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("IP가 내부 주소면 IP 제한만 건너뛰고(이메일·전체 한도는 센다) 보낸다")
    void privateIpSkipsLimit() {
        given(store.tryStartResendCooldown("tester@example.com")).willReturn(true);
        given(store.tryCountEmailDaily("tester@example.com")).willReturn(true);
        given(store.tryCountGlobalDaily()).willReturn(true);

        useCase.sendSignUpCode("tester@example.com", "10.42.0.15");

        verify(store, never()).tryCountIp(anyString());
        verify(emailSender).send(eq("tester@example.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("메일을 못 보내면 코드와 1분 간격을 지우고 503 EMAIL_SEND_FAILED")
    void sendFailed() {
        allowAll("tester@example.com", PUBLIC_IP);
        willThrow(new MailSendException("smtp down")).given(emailSender).send(anyString(), anyString(), anyString());

        assertError(() -> useCase.sendSignUpCode("tester@example.com", PUBLIC_IP), MemberErrorCode.EMAIL_SEND_FAILED);
        verify(store).deleteCode("tester@example.com");
        verify(store).clearResendCooldown("tester@example.com");
    }

    @Test
    @DisplayName("코드가 맞으면 소문자 이메일로 인증 토큰을 발급한다")
    void verifyMatched() {
        given(store.checkCode("tester@example.com", "123456", EmailVerificationPolicy.MAX_CODE_ATTEMPTS))
                .willReturn(EmailVerificationStore.CodeCheck.MATCHED);
        given(store.issueToken("tester@example.com")).willReturn("token-1");

        assertThat(useCase.verifySignUpCode("Tester@example.com", "123456")).isEqualTo("token-1");
    }

    @Test
    @DisplayName("틀리면 INVALID_EMAIL_CODE, 횟수 초과·코드 없음은 EMAIL_CODE_EXPIRED이고 토큰을 발급하지 않는다")
    void verifyRejected() {
        given(store.checkCode(eq("tester@example.com"), anyString(), eq(EmailVerificationPolicy.MAX_CODE_ATTEMPTS)))
                .willReturn(EmailVerificationStore.CodeCheck.WRONG, EmailVerificationStore.CodeCheck.ATTEMPTS_EXCEEDED,
                        EmailVerificationStore.CodeCheck.NOT_FOUND);

        assertError(() -> useCase.verifySignUpCode("tester@example.com", "000000"), MemberErrorCode.INVALID_EMAIL_CODE);
        assertError(() -> useCase.verifySignUpCode("tester@example.com", "000000"), MemberErrorCode.EMAIL_CODE_EXPIRED);
        assertError(() -> useCase.verifySignUpCode("tester@example.com", "000000"), MemberErrorCode.EMAIL_CODE_EXPIRED);
        verify(store, never()).issueToken(anyString());
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
}
