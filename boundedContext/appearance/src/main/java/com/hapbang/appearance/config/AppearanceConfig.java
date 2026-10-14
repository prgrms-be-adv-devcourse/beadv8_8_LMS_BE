package com.hapbang.appearance.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hapbang.appearance.domain.AppearancePolicy;
import com.hapbang.appearance.domain.HpTaskRetryPolicy;
import com.hapbang.appearance.shared.time.AppearanceTime;

/**
 * 출연 모듈 설정.
 * <ul>
 *     <li>정책 수치({@code appearance.policy.*})를 {@link AppearancePolicyProperties}로 읽어 도메인의 {@link AppearancePolicy}로 등록한다.
 *     값은 {@code appearance.yml}에 있고, 전역 application.yaml의 {@code spring.config.import}로 읽힌다.</li>
 *     <li>배치 설정({@code appearance.batch.*})을 {@link AppearanceBatchProperties}로 읽고, HP 작업 재시도 정책을 등록한다.</li>
 *     <li>현재 시각은 {@link #CLOCK} 빈으로만 얻는다. 테스트에서는 고정 시각의 Clock으로 바꿔 끼운다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AppearancePolicyProperties.class, AppearanceBatchProperties.class})
public class AppearanceConfig {

    /**
     * 다른 모듈이 Clock 빈을 만들어도 겹치지 않도록 이름을 구분한다. 주입할 때는 {@code @Qualifier(CLOCK)}를 붙인다.
     */
    public static final String CLOCK = "appearanceClock";

    @Bean
    AppearancePolicy appearancePolicy(AppearancePolicyProperties properties) {
        return properties.toPolicy();
    }

    @Bean
    HpTaskRetryPolicy hpTaskRetryPolicy(AppearanceBatchProperties properties) {
        return properties.toRetryPolicy();
    }

    @Bean(CLOCK)
    Clock appearanceClock() {
        return Clock.system(AppearanceTime.ZONE);
    }
}
