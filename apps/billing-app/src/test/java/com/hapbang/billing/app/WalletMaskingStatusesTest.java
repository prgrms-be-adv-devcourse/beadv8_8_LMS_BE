package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.billing.domain.ExchangeStatus;

// 파기 때 계좌를 지울 끝난 환전과 계좌를 남길 처리 중인 환전이 모든 환전 상태를 겹치지 않게 나누는지 확인한다 (#674, BD-144).
// 환전 상태를 새로 만들면 이 테스트가 실패해, 그 상태를 어느 쪽에 둘지(송금에 계좌가 필요한지) 정하게 한다.
class WalletMaskingStatusesTest {

    @Test
    @DisplayName("모든 환전 상태는 끝난 상태와 처리 중 상태 중 정확히 한 곳에만 있다")
    void everyStatusIsFinishedOrInProgress() {
        EnumSet<ExchangeStatus> finished = EnumSet.copyOf(WalletMaskingUseCase.EXCHANGE_FINISHED_STATUSES);
        EnumSet<ExchangeStatus> inProgress = EnumSet.copyOf(WalletWithdrawalBlocks.EXCHANGE_IN_PROGRESS_STATUSES);

        assertThat(finished).doesNotContainAnyElementsOf(inProgress);
        EnumSet<ExchangeStatus> all = EnumSet.copyOf(finished);
        all.addAll(inProgress);
        assertThat(all).isEqualTo(EnumSet.allOf(ExchangeStatus.class));
    }
}
