package com.hapbang.appearance.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.hapbang.appearance.domain.AppearancePolicy;
import com.hapbang.appearance.domain.AppearanceReviewPolicy;
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
    @DisplayName("appearance.yml의 배치 값(보류 포기 기준 1시간 포함)과 HP 작업 재시도 정책(1분부터 최대 1시간, 10회, 지터 0.2)이 들어온다")
    void bindsBatchFromYaml() {
        contextRunner.run(context -> {
            AppearanceBatchProperties batch = context.getBean(AppearanceBatchProperties.class);
            HpTaskRetryPolicy retryPolicy = context.getBean(HpTaskRetryPolicy.class);

            assertThat(batch.batchSize()).isEqualTo(100);
            assertThat(batch.pendingGrace()).isEqualTo(Duration.ofMinutes(1));
            assertThat(batch.holdVoidAfter()).isEqualTo(Duration.ofHours(1));
            assertThat(retryPolicy).isEqualTo(new HpTaskRetryPolicy(Duration.ofMinutes(1), Duration.ofHours(1), 10, 0.2));
        });
    }

    @Test
    @DisplayName("appearance.yml의 리뷰 작성 기간은 비어 있어 무기한이다 (#512)")
    void reviewPolicyIsUnlimitedByDefault() {
        contextRunner.run(context -> {
            AppearanceReviewPolicy policy = context.getBean(AppearanceReviewPolicy.class);

            assertThat(policy.writePeriod()).isNull();
            assertThat(policy.hasWritePeriod()).isFalse();
        });
    }

    @Test
    @DisplayName("리뷰 작성 기간을 채우면(예: 14d) 그 기간이 들어오고, 0이면 앱이 뜨지 않는다 (#512)")
    void reviewPolicyWithPeriod() {
        contextRunner.withPropertyValues("appearance.review.write-period=14d")
                .run(context -> assertThat(context.getBean(AppearanceReviewPolicy.class).writePeriod())
                        .isEqualTo(Duration.ofDays(14)));
        contextRunner.withPropertyValues("appearance.review.write-period=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("보류 포기 기준(hold-void-after)이 대기 시간(pending-grace)보다 짧거나 같으면 앱이 뜨지 않는다 (#536)")
    void holdVoidAfterLongerThanGrace() {
        contextRunner.withPropertyValues("appearance.batch.hold-void-after=1m")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("appearance.batch.hold-void-after=30s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("appearance.yml의 HP 대사 값: 진행 중 유예 2시간, 비교 범위 7일, 지갑 조회 500건 (#581), 멈춘 실행 정리 기준 1시간 (#821)")
    void bindsHpReconciliationFromYaml() {
        contextRunner.run(context -> {
            AppearanceHpReconciliationProperties properties =
                    context.getBean(AppearanceHpReconciliationProperties.class);

            assertThat(properties.settleGrace()).isEqualTo(Duration.ofHours(2));
            assertThat(properties.lookback()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.pageSize()).isEqualTo(500);
            assertThat(properties.abandonAfter()).isEqualTo(Duration.ofHours(1));
        });
    }

    @Test
    @DisplayName("HP 대사: 유예가 음수, 비교 범위가 유예 이하, 지갑 조회 건수가 1~500 밖, 멈춘 실행 정리 기준이 0 이하이면 앱이 뜨지 않는다 (#581, #821)")
    void hpReconciliationValidation() {
        contextRunner.withPropertyValues("appearance.hp-reconciliation.settle-grace=-1s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("appearance.hp-reconciliation.lookback=2h")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("appearance.hp-reconciliation.page-size=0")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("appearance.hp-reconciliation.page-size=501")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("appearance.hp-reconciliation.abandon-after=0s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("appearance.hp-reconciliation.page-size=1",
                        "appearance.hp-reconciliation.lookback=121m")
                .run(context -> assertThat(context).hasNotFailed());
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
