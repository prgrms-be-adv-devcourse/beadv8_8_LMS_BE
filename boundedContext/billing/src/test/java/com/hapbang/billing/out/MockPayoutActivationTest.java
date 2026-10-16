package com.hapbang.billing.out;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.env.Profiles;
import org.springframework.mock.env.MockEnvironment;

// 가상 송금 Mock 활성 조건(#288): enabled=true AND 활성 프로필이 하나 이상 AND 활성 프로필이 전부 local 또는 test.
// 실제 활성 프로필만 보며 프로필 미지정·기본 프로필(default, spring.profiles.default=local)은 local·test로 보지 않는다.
class MockPayoutActivationTest {

    @ParameterizedTest(name = "profiles=[{0}], enabled={1} -> {2}")
    @CsvSource(delimiter = '|', value = {
            "local      | true  | true",
            "test       | true  | true",
            "local      | false | false",
            "test       | false | false",
            "           | true  | false",  // 프로필 없음
            "dev        | true  | false",
            "prod       | true  | false",
            "staging    | true  | false",
            "local,dev  | true  | false",
            "local,prod | true  | false",
            "test,prod  | true  | false",
            "local,test | true  | true",
            "local,staging | true | false", // 허용 목록 밖 프로필이 섞이면 꺼진다
            "test,staging  | true | false",
            "local,test,staging | true | false",
            "local,qa      | true | false",
            "local,local   | true | true",
            "           | false | false",
    })
    @DisplayName("활성 프로필과 설정 조합별 활성 여부")
    void activation(String profiles, boolean enabled, boolean expected) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(MockPayoutActivation.ENABLED_PROPERTY, String.valueOf(enabled));
        if (profiles != null && !profiles.isBlank()) {
            environment.setActiveProfiles(profiles.split(","));
        }

        assertThat(MockPayoutActivation.isActive(environment)).isEqualTo(expected);
    }

    @Test
    @DisplayName("프로필을 지정하지 않고 기본 프로필만 local·test여도(spring.profiles.default=local) 켜지지 않는다")
    void defaultProfileIsNotActiveProfile() {
        MockEnvironment local = new MockEnvironment().withProperty(MockPayoutActivation.ENABLED_PROPERTY, "true");
        local.setDefaultProfiles("local");
        MockEnvironment test = new MockEnvironment().withProperty(MockPayoutActivation.ENABLED_PROPERTY, "true");
        test.setDefaultProfiles("test");

        assertThat(local.acceptsProfiles(Profiles.of("local"))).isTrue();
        assertThat(MockPayoutActivation.isActive(local)).isFalse();
        assertThat(MockPayoutActivation.isActive(test)).isFalse();
    }

    @Test
    @DisplayName("enabled 설정이 없으면 꺼진다(기본 false)")
    void disabledByDefault() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        assertThat(MockPayoutActivation.isActive(environment)).isFalse();
    }
}
