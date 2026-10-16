package com.hapbang.global.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

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

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(InternalApiProperties.class)
    static class PropertiesConfig {
    }
}
