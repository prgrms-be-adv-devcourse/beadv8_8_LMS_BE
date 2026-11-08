package com.hapbang.lecture.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * 실행 설정(application.yaml)이 Spring Boot 기본 사용자(user)와 생성 비밀번호를 만들지 않는지 확인한다 (#733).
 * <p>
 * 앱은 Gateway 사용자 헤더와 내부 API 키로 인증하고 {@code UserDetailsService}가 없다. 그대로 두면 Boot의
 * {@link UserDetailsServiceAutoConfiguration}이 기본 사용자를 만들고 생성 비밀번호를 기동 로그(WARN)에 남긴다.
 * <p>
 * 테스트 classpath에는 루트 build가 넣은 OAuth2 client(spring-boot-starter-security-oauth2-client-test)가 있어 이 자동 설정이
 * 원래 붙지 않는다. 그래서 OAuth2 client·resource server·SAML 패키지를 숨겨 운영 classpath를 흉내 내고, 실제 설정 파일과
 * Boot 보안 자동 설정만 올린다. 앱 전체 빈 조립은 확인하지 않는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class DefaultSecurityUserTest {

    private static final String GENERATED_PASSWORD_LOG = "Using generated security password";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withClassLoader(new FilteredClassLoader(
                    "org.springframework.security.oauth2.client.",
                    "org.springframework.security.oauth2.server.resource.",
                    "org.springframework.security.saml2."))
            .withUserConfiguration(BootSecurityAutoConfiguration.class);

    // 아래 테스트가 운영 조건을 재현하는지 보는 대조군이다. 설정 파일 없이는 기본 사용자가 생겨야 한다
    @Test
    void 설정_파일을_읽지_않으면_기본_사용자와_생성_비밀번호가_생긴다(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasSingleBean(InMemoryUserDetailsManager.class);
            assertThat(output).contains(GENERATED_PASSWORD_LOG);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "prod"})
    void 실행_설정은_기본_사용자와_생성_비밀번호를_만들지_않는다(String profile, CapturedOutput output) {
        runner.withPropertyValues("spring.profiles.active=" + profile)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(UserDetailsService.class);
                    assertThat(output).doesNotContain(GENERATED_PASSWORD_LOG);
                });
    }

    // spring.autoconfigure.exclude를 읽는 경로(@ImportAutoConfiguration)로 올린다. AutoConfigurations.of로 올리면 제외 설정을 읽지 않는다
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    static class BootSecurityAutoConfiguration {
    }
}
