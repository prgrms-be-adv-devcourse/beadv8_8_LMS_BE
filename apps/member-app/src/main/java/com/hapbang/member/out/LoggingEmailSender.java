package com.hapbang.member.out;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 메일 대신 로그로 남긴다 (#557). 두 경우에 만든다.
 * <ul>
 *     <li>운영(prod)이 아니고 메일 계정이 없을 때(로컬 실행·테스트)</li>
 *     <li>{@code hapbang.member.mail.log-only=true}일 때. prod 프로필로 띄우는 로컬 k3d 검증만 켠다(apps/member-app/k8s/overlays/local).
 *     운영 overlay에는 이 값이 없다</li>
 * </ul>
 * 인증 코드가 로그에 찍히므로 운영에서는 만들지 않는다. 운영은 메일 계정이 없으면 설정(${MAIL_USERNAME})부터 실패해 앱이 시작하지 않는다
 */
@Slf4j
@Component
@Conditional(LoggingEmailSender.WhenLogOnly.class)
public class LoggingEmailSender implements EmailSender {

    /** 로그로만 남기기가 켜졌거나, 운영이 아니고 메일 계정이 없을 때 */
    static class WhenLogOnly implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();
            if (environment.getProperty("hapbang.member.mail.log-only", Boolean.class, false)) {
                return true;
            }
            String username = environment.getProperty("spring.mail.username", "");
            return username.isBlank() && !environment.matchesProfiles("prod");
        }
    }

    @Override
    public void send(String to, String subject, String text) {
        log.info("메일 계정이 없어 메일 대신 로그로 남깁니다. to={}, subject={}\n{}", to, subject, text);
    }
}
