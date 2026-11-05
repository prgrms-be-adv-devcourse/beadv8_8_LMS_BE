package com.hapbang.member.out;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * Gmail SMTP로 메일을 보낸다 (#557). 메일 계정({@code spring.mail.username})이 있고, 로그로만 남기기
 * ({@code hapbang.member.mail.log-only})가 꺼져 있을 때만 만든다.
 * 보낸 사람은 그 계정이고 표시 이름은 "합방"이다. SMTP 연결·응답 제한 시간은 application.yaml(5초)
 */
@Component
@ConditionalOnExpression("!'${spring.mail.username:}'.isBlank() and !${hapbang.member.mail.log-only:false}")
public class SmtpEmailSender implements EmailSender {

    static final String SENDER_NAME = "합방";

    private final JavaMailSender mailSender;
    private final String from;

    SmtpEmailSender(JavaMailSender mailSender, @Value("${spring.mail.username}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String text) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(from, SENDER_NAME);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, false);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailSendException("메일을 만들지 못했습니다.", e);
        }
        mailSender.send(message);
    }
}
