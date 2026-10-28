package com.hapbang.appearance.global.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

// 내부 API 키가 없으면 앱이 시작하지 않는다 (#300). 서버 env에 키를 빠뜨린 배포가 헬스 체크에서 실패하게 한다.
class InternalApiPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    void 내부_API_키가_비어_있으면_앱이_뜨지_않는다() {
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("hapbang.internal-api.key= ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 내부_API_키가_있으면_뜨고_문자열로_키를_드러내지_않는다() {
        runner.withPropertyValues("hapbang.internal-api.key=secret-value")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    InternalApiProperties properties = context.getBean(InternalApiProperties.class);
                    assertThat(properties.key()).isEqualTo("secret-value");
                    assertThat(properties.toString()).doesNotContain("secret-value");
                });
    }

    // #307 보강: 공백뿐인 값(여러 칸·탭)도 키가 아니다
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void 빈_값이나_공백뿐인_키는_앱이_뜨지_않는다(String key) {
        runner.withPropertyValues("hapbang.internal-api.key=" + key)
                .run(context -> assertThat(context).hasFailed());
    }

    // #307 보강: 서버는 환경 변수 HAPBANG_INTERNAL_API_KEY로 키를 넣는다(relaxed binding)
    @Test
    void 환경_변수_HAPBANG_INTERNAL_API_KEY가_키로_바인딩된다() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("HAPBANG_INTERNAL_API_KEY", "env-bound-key"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(InternalApiProperties.class).key()).isEqualTo("env-bound-key");
                });
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(InternalApiProperties.class)
    static class PropertiesConfig {
    }
}
