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
        given(store.tryCountIp(PUBLIC_IP)).willReturn(true);
        given(store.tryStartResendCooldown("tester@example.com")).willReturn(true);

        useCase.sendSignUpCode(" Tester@Example.com ", PUBLIC_IP);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(store).saveCode(eq("tester@example.com"), code.capture());
        assertThat(code.getValue()).matches("\\d{6}");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq("tester@example.com"), eq(MemberEmailVerificationUseCase.SUBJECT), body.capture());
        assertThat(body.getValue()).contains(code.getValue(), "10분");
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 409 DUPLICATE_EMAIL이고 아무것도 세거나 보내지 않는다")
    void duplicateEmail() {
        given(userRepository.existsByEmailIgnoreCase("tester@example.com")).willReturn(true);

        assertError(() -> useCase.sendSignUpCode("tester@example.com", PUBLIC_IP), MemberErrorCode.DUPLICATE_EMAIL);
        verify(store, never()).tryCountIp(anyString());
        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("IP 한도를 넘으면 429 EMAIL_CODE_TOO_MANY_REQUESTS이고 보내지 않는다")
    void ipLimitExceeded() {
        given(store.tryCountIp(PUBLIC_IP)).willReturn(false);

        assertError(() -> useCase.sendSignUpCode("tester@example.com", PUBLIC_IP),
                MemberErrorCode.EMAIL_CODE_TOO_MANY_REQUESTS);
        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("같은 이메일 재발송 간격 안이면 429 EMAIL_CODE_RESEND_TOO_SOON이고 보내지 않는다")
    void resendTooSoon() {
        given(store.tryCountIp(PUBLIC_IP)).willReturn(true);
        given(store.tryStartResendCooldown("tester@example.com")).willReturn(false);

        assertError(() -> useCase.sendSignUpCode("tester@example.com", PUBLIC_IP),
                MemberErrorCode.EMAIL_CODE_RESEND_TOO_SOON);
        verify(store, never()).saveCode(anyString(), anyString());
    }

    @Test
    @DisplayName("IP가 내부 주소면 IP 제한을 건너뛰고 보낸다 (모든 사람이 한 IP로 보여 가입이 막히지 않게)")
    void privateIpSkipsLimit() {
        given(store.tryStartResendCooldown("tester@example.com")).willReturn(true);

        useCase.sendSignUpCode("tester@example.com", "10.42.0.15");

        verify(store, never()).tryCountIp(anyString());
        verify(emailSender).send(eq("tester@example.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("메일을 못 보내면 코드와 재발송 간격을 지우고 503 EMAIL_SEND_FAILED")
    void sendFailed() {
        given(store.tryCountIp(PUBLIC_IP)).willReturn(true);
        given(store.tryStartResendCooldown("tester@example.com")).willReturn(true);
        willThrow(new MailSendException("smtp down")).given(emailSender).send(anyString(), anyString(), anyString());

        assertError(() -> useCase.sendSignUpCode("tester@example.com", PUBLIC_IP), MemberErrorCode.EMAIL_SEND_FAILED);
        verify(store).deleteCode("tester@example.com");
        verify(store).clearResendCooldown("tester@example.com");
    }

    @Test
    @DisplayName("코드가 맞으면 소문자 이메일로 인증 토큰을 발급한다")
    void verifyMatched() {
        given(store.checkCode("tester@example.com", "123456", MemberEmailVerificationUseCase.MAX_CODE_ATTEMPTS))
                .willReturn(EmailVerificationStore.CodeCheck.MATCHED);
        given(store.issueToken("tester@example.com")).willReturn("token-1");

        assertThat(useCase.verifySignUpCode("Tester@example.com", "123456")).isEqualTo("token-1");
    }

    @Test
    @DisplayName("틀리면 INVALID_EMAIL_CODE, 횟수 초과·코드 없음은 EMAIL_CODE_EXPIRED이고 토큰을 발급하지 않는다")
    void verifyRejected() {
        given(store.checkCode(eq("tester@example.com"), anyString(), eq(MemberEmailVerificationUseCase.MAX_CODE_ATTEMPTS)))
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
}
