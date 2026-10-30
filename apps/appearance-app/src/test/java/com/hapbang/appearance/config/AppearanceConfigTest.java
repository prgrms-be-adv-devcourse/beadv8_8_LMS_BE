package com.hapbang.appearance.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.hapbang.appearance.domain.AppearancePolicy;
import com.hapbang.appearance.domain.HpTaskRetryPolicy;
import com.hapbang.appearance.shared.time.AppearanceTime;

/**
 * DB 없이 출연 설정 클래스만 띄워서, appearance.yml 값이 제대로 들어오는지 확인한다.
 * ConfigDataApplicationContextInitializer가 테스트용 application.yaml과 그 안의 spring.config.import를 실제 앱처럼 읽는다.
 */
class AppearanceConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(AppearanceConfig.class);

    @Test
    @DisplayName("appearance.yml의 정책 값이 AppearancePolicy로 들어온다")
    void bindsPolicyFromYaml() {
        contextRunner.run(context -> {
            AppearancePolicy policy = context.getBean(AppearancePolicy.class);

            assertThat(policy.minimumFee()).isEqualTo(20_000);
            assertThat(policy.penaltyRatePercent()).isEqualTo(30);
            assertThat(policy.confirmPeriod()).isEqualTo(Duration.ofDays(7));
            assertThat(policy.maxCancelRequestCount()).isEqualTo(2);
            assertThat(policy.maxRecruitCount()).isEqualTo(10);
        });
    }

    @Test
    @DisplayName("appearance.yml의 배치 값과 HP 작업 재시도 정책(1분부터 최대 1시간, 10회, 지터 0.2)이 들어온다")
    void bindsBatchFromYaml() {
        contextRunner.run(context -> {
            AppearanceBatchProperties batch = context.getBean(AppearanceBatchProperties.class);
            HpTaskRetryPolicy retryPolicy = context.getBean(HpTaskRetryPolicy.class);

            assertThat(batch.batchSize()).isEqualTo(100);
            assertThat(batch.pendingGrace()).isEqualTo(Duration.ofMinutes(1));
            assertThat(retryPolicy).isEqualTo(new HpTaskRetryPolicy(Duration.ofMinutes(1), Duration.ofHours(1), 10, 0.2));
        });
    }

    /**
     * 설정 우선순위만 확인한다. 정책 값은 appearance.yml에서만 바꾼다(운영 규칙).
     */
    @Test
    @DisplayName("설정 우선순위: 외부 설정이 appearance.yml 값보다 우선한다")
    void externalPropertyOverridesYaml() {
        contextRunner.withPropertyValues("appearance.policy.minimum-fee=30000")
                .run(context -> assertThat(context.getBean(AppearancePolicy.class).minimumFee()).isEqualTo(30_000));
    }

    @Test
    @DisplayName("설정 바인딩은 config의 AppearancePolicyProperties가 하고, 도메인 AppearancePolicy에는 어노테이션이 없다")
    void policyIsFrameworkFree() {
        assertThat(AppearancePolicy.class.getAnnotations()).isEmpty();
        contextRunner.run(context -> assertThat(context).hasSingleBean(AppearancePolicyProperties.class)
                .hasSingleBean(AppearancePolicy.class));
    }

    @Test
    @DisplayName("출연 Clock은 한국 시간대를 쓴다")
    void clockUsesSeoulZone() {
        contextRunner.run(context -> {
            Clock clock = context.getBean(AppearanceConfig.CLOCK, Clock.class);

            assertThat(clock.getZone()).isEqualTo(AppearanceTime.ZONE);
        });
    }
}
