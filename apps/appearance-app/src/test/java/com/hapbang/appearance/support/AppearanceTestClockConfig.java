package com.hapbang.appearance.support;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.hapbang.appearance.config.AppearanceConfig;

/**
 * 출연 통합 테스트에서 {@code @Qualifier(AppearanceConfig.CLOCK)}로 주입받는 곳에 {@link AppearanceTestClock}을 넣는다 (#297).
 * 운영 Clock 빈은 그대로 두고, 같은 한정자 + {@code @Primary}로 이 시계가 선택되게 한다 (빈 덮어쓰기 설정 없이).
 */
@TestConfiguration(proxyBeanMethods = false)
public class AppearanceTestClockConfig {

    @Bean
    @Primary
    @Qualifier(AppearanceConfig.CLOCK)
    AppearanceTestClock appearanceTestClock() {
        return new AppearanceTestClock();
    }
}
