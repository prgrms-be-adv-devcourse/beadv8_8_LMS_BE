package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.hapbang.billing.out.WalletRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 잔액이 남은 닫힌 지갑 수 지표(#623)가 값을 저장해 두지 않고 수집할 때마다 세며, 세지 못하면 NaN을 내는지 DB 없이 확인한다.
class ClosedWalletBalanceMetricsTest {

    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    ClosedWalletBalanceMetricsTest() {
        new ClosedWalletBalanceMetrics(walletRepository).bindTo(registry);
    }

    @Test
    @DisplayName("수집할 때마다 DB에서 세므로 배치 없이도 지금 값이다(재시작 직후 0으로 보이지 않는다)")
    void countsOnEveryScrape() {
        given(walletRepository.countClosedWithBalance()).willReturn(2L, 3L);

        assertThat(value()).isEqualTo(2.0);
        assertThat(value()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("세지 못하면 예전 값이나 0이 아니라 NaN이다")
    void nanOnFailure() {
        given(walletRepository.countClosedWithBalance()).willReturn(2L)
                .willThrow(new DataAccessResourceFailureException("connection lost"));

        assertThat(value()).isEqualTo(2.0);
        assertThat(value()).isNaN();
    }

    private double value() {
        return registry.get(ClosedWalletBalanceMetrics.METRIC).gauge().value();
    }
}
