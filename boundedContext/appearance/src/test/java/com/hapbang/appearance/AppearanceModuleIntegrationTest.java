package com.hapbang.appearance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.AppearancePolicy;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.out.billing.FakeBillingClient;
import com.hapbang.appearance.shared.time.AppearanceTime;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 출연 모듈 전체를 PostgreSQL 컨테이너와 함께 띄워 기반 빈이 모두 준비되는지 확인한다. Docker가 실행 중이어야 한다.
 */
@IntegrationTest
class AppearanceModuleIntegrationTest {

    @Autowired
    private AppearancePolicy policy;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    private Clock clock;

    @Autowired
    private BillingClient billingClient;

    @Test
    @DisplayName("출연 모듈의 정책, Clock, Fake Billing이 등록된다")
    void loadsModuleBeans() {
        assertThat(policy.minimumFee()).isEqualTo(20_000);
        assertThat(clock.getZone()).isEqualTo(AppearanceTime.ZONE);
        assertThat(billingClient).isInstanceOf(FakeBillingClient.class);
    }
}
