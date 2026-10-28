package com.hapbang.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

// Internal API 키(hapbang.internal-api.key, 환경 변수 HAPBANG_INTERNAL_API_KEY, #300)의 환경별 설정을 실제 설정 파일
// (application.yaml + application-{profile}.yaml)로 검증한다 (#307). 이 PC의 OS 환경 변수는 빼고, 키를 넣지 않은 서버·로컬 실행과
// 같은 조건을 만든다. 키 검사는 global InternalApiProperties(패키지 전용 record)를 앱과 같은 Binder로 만들어 확인한다.
class InternalApiKeyConfigTest {

    private static final String PROPERTIES_CLASS = "com.hapbang.global.internal.InternalApiProperties";

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    @DisplayName("서버 프로필(prod·dev)은 HAPBANG_INTERNAL_API_KEY가 없으면 기동 실패한다")
    void serverProfilesRequireKey(String profile) {
        // 공통 설정이 없는 값을 빈 값으로 두므로 키 검사(InternalApiProperties)가 거부한다. 풀 수 없는 ${...}를 키로 쓰지 않는다
        assertThatThrownBy(() -> bindKey(profile, null))
                .rootCause().hasMessageContaining("hapbang.internal-api.key");
    }

    @ParameterizedTest
    @CsvSource(value = {"prod|''", "prod|'   '", "dev|''", "dev|'  '"}, delimiter = '|')
    @DisplayName("서버 프로필은 빈 값·공백뿐인 HAPBANG_INTERNAL_API_KEY도 기동 실패한다")
    void serverProfilesRejectBlankKey(String profile, String key) {
        assertThatThrownBy(() -> bindKey(profile, key))
                .rootCause().hasMessageContaining("hapbang.internal-api.key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev", "local"})
    @DisplayName("HAPBANG_INTERNAL_API_KEY를 넣으면 그 값이 키가 된다")
    void bindsEnvironmentKey(String profile) throws Exception {
        assertThat(bindKey(profile, "deploy-like-key-value")).isEqualTo("deploy-like-key-value");
    }

    @Test
    @DisplayName("현재 동작(#300): 로컬 프로필은 키가 없으면 기본값 local-placeholder로 뜬다(서버와 다르다)")
    void localProfileFallsBackToPlaceholder() throws Exception {
        assertThat(bindKey("local", null)).isEqualTo("local-placeholder");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yaml", "application-local.yaml", "application-dev.yaml",
            "application-prod.yaml"})
    @DisplayName("설정 파일에 실제 키를 두지 않는다: 공통 설정은 기본값 없이 환경 변수만 연결하고, 로컬만 placeholder 기본값을 둔다")
    void noHardcodedKey(String file) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(file));
        Properties properties = yaml.getObject();
        String key = properties.getProperty("hapbang.internal-api.key");

        switch (file) {
            case "application.yaml" -> assertThat(key).isEqualTo("${HAPBANG_INTERNAL_API_KEY:}");
            case "application-local.yaml" -> assertThat(key).isEqualTo("${HAPBANG_INTERNAL_API_KEY:local-placeholder}");
            default -> assertThat(properties.stringPropertyNames()).noneMatch(name -> name.startsWith("hapbang.internal-api"));
        }
        // #307 초안의 설정명·환경 변수는 남아 있지 않다
        assertThat(properties.stringPropertyNames()).noneMatch(name -> name.startsWith("internal."));
        assertThat(properties.values()).noneMatch(value -> String.valueOf(value).contains("${INTERNAL_API_KEY"));
    }

    // 실제 설정 파일로 환경을 만들고(빈은 없음) 앱과 같은 Binder로 InternalApiProperties를 만든다. key가 null이면 환경 변수를 넣지 않는다.
    private static String bindKey(String profile, String key) throws Exception {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(Empty.class)
                .web(WebApplicationType.NONE)
                .profiles(profile)
                .initializers(context -> context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME));
        if (key != null) {
            builder.properties("HAPBANG_INTERNAL_API_KEY=" + key);
        }
        Class<?> type = Class.forName(PROPERTIES_CLASS);
        try (ConfigurableApplicationContext context = builder.run()) {
            Object properties = Binder.get(context.getEnvironment()).bindOrCreate("hapbang.internal-api",
                    Bindable.of(type));
            java.lang.reflect.Method accessor = type.getRecordComponents()[0].getAccessor();
            accessor.setAccessible(true);
            return (String) accessor.invoke(properties);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }
}
