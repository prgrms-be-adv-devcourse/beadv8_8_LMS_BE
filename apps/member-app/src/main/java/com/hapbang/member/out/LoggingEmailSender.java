package com.hapbang.member.out;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 메일 계정이 없을 때(로컬·테스트) 메일 대신 로그로 남긴다 (#557). 인증 코드가 로그에 찍히므로 운영(prod)에서는 만들지 않는다.
 * 운영은 메일 계정이 없으면 설정(${MAIL_USERNAME})부터 실패해 앱이 시작하지 않는다
 */
@Slf4j
@Component
@Profile("!prod")
@ConditionalOnExpression("'${spring.mail.username:}'.isBlank()")
public class LoggingEmailSender implements EmailSender {

    @Override
    public void send(String to, String subject, String text) {
        log.info("메일 계정이 없어 메일 대신 로그로 남깁니다. to={}, subject={}\n{}", to, subject, text);
    }
}
