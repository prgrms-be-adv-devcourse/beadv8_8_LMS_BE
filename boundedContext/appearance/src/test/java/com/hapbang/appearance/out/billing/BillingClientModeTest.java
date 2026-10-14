package com.hapbang.appearance.out.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.hapbang.shared.billing.port.BillingPort;

/**
 * {@code appearance.billing.mode} 값에 따라 어떤 {@link BillingClient}가 등록되는지 DB 없이 확인한다 (#182).
 * 실제 Billing을 부르는 구현은 값을 정확히 {@code billing-port}로 줄 때만 켜지고, 그 밖의 값은 실제 금융 작업으로 이어지지 않는다.
 */
class BillingClientModeTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(BillingPort.class, () -> mock(BillingPort.class))
            .withUserConfiguration(FakeBillingClient.class, BillingPortClient.class);

    @Test
    @DisplayName("값이 없거나 fake면 Fake만 등록된다(기본값)")
    void fakeByDefault() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(BillingClient.class)
                .hasSingleBean(FakeBillingClient.class).doesNotHaveBean(BillingPortClient.class));
        contextRunner.withPropertyValues("appearance.billing.mode=fake")
                .run(context -> assertThat(context).hasSingleBean(FakeBillingClient.class)
                        .doesNotHaveBean(BillingPortClient.class));
    }

    @Test
    @DisplayName("billing-port면 BillingPortClient만 등록되어 shared BillingPort로 위임한다")
    void billingPortMode() {
        contextRunner.withPropertyValues("appearance.billing.mode=billing-port")
                .run(context -> assertThat(context).hasSingleBean(BillingClient.class)
                        .hasSingleBean(BillingPortClient.class).doesNotHaveBean(FakeBillingClient.class));
    }

    @Test
    @DisplayName("값을 잘못 쓰면 어느 구현도 등록되지 않아 BillingClient를 쓰는 빈이 뜨지 않는다(조용히 실제·가짜로 바뀌지 않는다)")
    void unknownModeRegistersNothing() {
        for (String mode : new String[] {"billingport", "billing_port", "http", ""}) {
            contextRunner.withPropertyValues("appearance.billing.mode=" + mode)
                    .run(context -> assertThat(context).as(mode).doesNotHaveBean(BillingClient.class));
        }
    }

    @Test
    @DisplayName("Spring의 havingValue 비교는 대소문자를 구분하지 않아 BILLING-PORT·FAKE도 같은 값으로 본다")
    void modeIsCaseInsensitive() {
        contextRunner.withPropertyValues("appearance.billing.mode=BILLING-PORT")
                .run(context -> assertThat(context).hasSingleBean(BillingPortClient.class)
                        .doesNotHaveBean(FakeBillingClient.class));
        contextRunner.withPropertyValues("appearance.billing.mode=FAKE")
                .run(context -> assertThat(context).hasSingleBean(FakeBillingClient.class)
                        .doesNotHaveBean(BillingPortClient.class));
    }
}
