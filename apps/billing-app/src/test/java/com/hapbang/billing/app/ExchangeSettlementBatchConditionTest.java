package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// 환전 정산·결과 확인 배치(#305)의 켜짐 조건: 공통 배치 스위치(hapbang.scheduling.enabled)와 배치별 스위치(기본 켬)가 모두 켜져야 빈이 생긴다.
// 테스트는 공통 스위치가 꺼져 있어 배치가 금융 데이터를 바꾸지 않는다(apps 테스트 설정·모듈 테스트 기본값).
class ExchangeSettlementBatchConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ExchangeSettlementUseCase.class, () -> mock(ExchangeSettlementUseCase.class))
            .withBean(ExchangePayoutUseCase.class, () -> mock(ExchangePayoutUseCase.class))
            .withUserConfiguration(ExchangeSettlementBatch.class, ExchangePayoutRecoveryBatch.class);

    @Test
    @DisplayName("공통 배치 스위치가 꺼져 있으면(테스트 기본) 두 배치 모두 없다")
    void offWhenSchedulingDisabled() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ExchangeSettlementBatch.class);
            assertThat(context).doesNotHaveBean(ExchangePayoutRecoveryBatch.class);
        });
        runner.withPropertyValues("hapbang.scheduling.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ExchangeSettlementBatch.class);
            assertThat(context).doesNotHaveBean(ExchangePayoutRecoveryBatch.class);
        });
    }

    @Test
    @DisplayName("공통 배치 스위치가 켜지면 배치별 스위치 기본값(켬)으로 두 배치가 생긴다")
    void onByDefault() {
        runner.withPropertyValues("hapbang.scheduling.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(ExchangeSettlementBatch.class);
            assertThat(context).hasSingleBean(ExchangePayoutRecoveryBatch.class);
        });
    }

    @Test
    @DisplayName("배치별 스위치로 정산 배치·결과 확인 배치를 따로 끌 수 있다")
    void individualSwitches() {
        runner.withPropertyValues("hapbang.scheduling.enabled=true",
                        "hapbang.billing.exchange.settlement.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ExchangeSettlementBatch.class);
                    assertThat(context).hasSingleBean(ExchangePayoutRecoveryBatch.class);
                });
        runner.withPropertyValues("hapbang.scheduling.enabled=true",
                        "hapbang.billing.exchange.payout.recovery-enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(ExchangeSettlementBatch.class);
                    assertThat(context).doesNotHaveBean(ExchangePayoutRecoveryBatch.class);
                });
    }
}
