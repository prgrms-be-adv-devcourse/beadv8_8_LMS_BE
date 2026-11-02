package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.hapbang.member.app.MemberFacade;

/** 초기 계정 실행기 (#520): local·prod 프로필에서만 켜지고, 프로필이 없는 모듈 테스트에서는 생기지 않는다 */
class MemberDataInitTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MemberFacade.class, () -> mock(MemberFacade.class))
            .withUserConfiguration(MemberDataInit.class);

    @ParameterizedTest
    @ValueSource(strings = {"local", "prod"})
    @DisplayName("local·prod 프로필이면 앱 시작 시 초기 계정을 만든다")
    void enabledInLocalAndProd(String profile) {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .run(context -> {
                    assertThat(context).hasSingleBean(MemberDataInit.class);
                    context.getBean(MemberDataInit.class).run(new DefaultApplicationArguments());
                    verify(context.getBean(MemberFacade.class)).createSeedAccounts();
                });
    }

    @Test
    @DisplayName("프로필이 없으면(모듈 테스트) 만들지 않는다")
    void disabledWithoutProfile() {
        runner.run(context -> assertThat(context).doesNotHaveBean(MemberDataInit.class));
    }
}
