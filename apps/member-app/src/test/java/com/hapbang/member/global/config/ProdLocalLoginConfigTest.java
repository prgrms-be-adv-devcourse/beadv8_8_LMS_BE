package com.hapbang.member.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** 운영 설정의 로컬 로그인 (#517): 첫 관리자를 만들려고 기본으로 켜고, 인프라가 환경 변수로 끈다 */
class ProdLocalLoginConfigTest {

    private static final String PROPERTY = "hapbang.local-login.enabled";

    @Test
    @DisplayName("운영 설정은 환경 변수가 없으면 로컬 로그인을 켠다")
    void enabledByDefault() throws IOException {
        assertThat(prodEnvironment(Map.of()).getProperty(PROPERTY)).isEqualTo("true");
    }

    @Test
    @DisplayName("HAPBANG_LOCAL_LOGIN_ENABLED=false면 운영에서 로컬 로그인을 끈다")
    void disabledByEnvironment() throws IOException {
        assertThat(prodEnvironment(Map.of("HAPBANG_LOCAL_LOGIN_ENABLED", "false")).getProperty(PROPERTY))
                .isEqualTo("false");
    }

    /** application-prod.yaml만 읽고, 주어진 값을 환경 변수처럼 그 위에 둔다 */
    private static StandardEnvironment prodEnvironment(Map<String, Object> environmentVariables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("test-env", environmentVariables));
        new YamlPropertySourceLoader().load("prod", new ClassPathResource("application-prod.yaml"))
                .forEach(environment.getPropertySources()::addLast);
        return environment;
    }
}
