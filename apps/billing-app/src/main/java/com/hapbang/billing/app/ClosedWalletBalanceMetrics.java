package com.hapbang.billing.app;

import org.springframework.stereotype.Component;

import com.hapbang.billing.out.WalletRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 탈퇴로 닫혔는데 쓸 수 있는 충전·수익 HP가 남은 지갑 수 {@code hapbang.billing.wallet.closed.with.balance} (#623, BD-141).
 * <p>
 * 닫힌 뒤 들어온 반환·지급은 환불·환전할 수 없어 회원이 복구하기 전까지 묶인다. 알림을 걸 수 있게 값을 저장해 두지 않고
 * <b>수집할 때마다 DB에서 센다</b>. 그래서 pod가 다시 뜬 직후에도 실제 값이고, 닫힌 지갑에 HP가 들어오면 다음 수집에 바로 보인다.
 * 세지 못하면(DB 연결 불가 등) 예전 값이나 0이 아니라 NaN(값 없음)을 낸다. 닫힌 지갑만 담은 부분 인덱스({@code idx_wallet_2})라 가볍다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClosedWalletBalanceMetrics implements MeterBinder {

    static final String METRIC = "hapbang.billing.wallet.closed.with.balance";

    private final WalletRepository walletRepository;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(METRIC, this, ClosedWalletBalanceMetrics::count)
                .description("탈퇴로 닫혔는데 쓸 수 있는 충전·수익 HP가 남은 지갑 수(수집 때마다 셈, 세지 못하면 NaN)")
                .register(registry);
    }

    double count() {
        try {
            return walletRepository.countClosedWithBalance();
        } catch (RuntimeException e) {
            log.warn("잔액이 남은 닫힌 지갑을 세지 못했다", e);
            return Double.NaN;
        }
    }
}
