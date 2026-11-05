package com.hapbang.member.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/** 메일 발송 (#557): SMTP 메일 내용과, 메일 계정 유무·프로필에 따라 어느 발송기가 뜨는지 */
class SmtpEmailSenderTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(JavaMailSender.class, () -> mock(JavaMailSender.class))
            .withUserConfiguration(SmtpEmailSender.class, LoggingEmailSender.class);

    @Test
    @DisplayName("보낸 사람은 메일 계정(표시 이름 합방), 받는 사람·제목·본문을 UTF-8 텍스트로 보낸다")
    void send() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        given(mailSender.createMimeMessage()).willReturn(new MimeMessage(Session.getInstance(new Properties())));
        SmtpEmailSender sender = new SmtpEmailSender(mailSender, "hapbang.noreply@gmail.com");

        sender.send("tester@example.com", "[합방] 이메일 인증 코드", "인증 코드: 123456");

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        MimeMessage message = sent.getValue();
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("hapbang.noreply@gmail.com");
        assertThat(from.getPersonal()).isEqualTo("합방");
        assertThat(message.getAllRecipients()).extracting(Object::toString).containsExactly("tester@example.com");
        assertThat(message.getSubject()).isEqualTo("[합방] 이메일 인증 코드");
        assertThat(message.getContent()).isEqualTo("인증 코드: 123456");
    }

    @Test
    @DisplayName("메일 계정이 있으면 SMTP 발송기만 뜬다")
    void smtpWhenUsernameSet() {
        contextRunner.withPropertyValues("spring.mail.username=hapbang.noreply@gmail.com")
                .run(context -> {
                    assertThat(context).hasSingleBean(EmailSender.class);
                    assertThat(context).hasSingleBean(SmtpEmailSender.class);
                });
    }

    @Test
    @DisplayName("메일 계정이 없으면(비어 있어도) 로컬·테스트는 로그 발송기만 뜬다")
    void loggingWhenUsernameMissing() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(LoggingEmailSender.class)
                .doesNotHaveBean(SmtpEmailSender.class));
        contextRunner.withPropertyValues("spring.mail.username=")
                .run(context -> assertThat(context).hasSingleBean(LoggingEmailSender.class));
    }

    @Test
    @DisplayName("로그로만 남기기를 켜면(로컬 k3d) prod 프로필이고 메일 계정이 있어도 로그 발송기만 뜬다")
    void logOnly() {
        contextRunner.withPropertyValues("spring.profiles.active=prod", "spring.mail.username=dummy@example.com",
                        "hapbang.member.mail.log-only=true")
                .run(context -> assertThat(context).hasSingleBean(LoggingEmailSender.class)
                        .doesNotHaveBean(SmtpEmailSender.class));
    }

    @Test
    @DisplayName("운영(prod)은 메일 계정이 없어도 로그 발송기를 만들지 않는다(인증 코드가 로그에 남지 않게)")
    void noLoggingSenderInProd() {
        contextRunner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).doesNotHaveBean(EmailSender.class));
    }
}
