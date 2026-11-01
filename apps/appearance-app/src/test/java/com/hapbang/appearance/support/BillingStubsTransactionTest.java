package com.hapbang.appearance.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;

/**
 * Billing 대역이 DB 트랜잭션 안에서 받은 요청을 잡아내는지 (#463). 이 감시가 Billing 대역을 쓰는 통합·E2E 테스트에서
 * "지갑은 DB 트랜잭션 밖에서 부른다"를 확인하므로, 감시 자체가 동작하는지 먼저 본다.
 */
@AppearanceIntegrationTest
class BillingStubsTransactionTest {

    @Autowired
    BillingClient billingClient;

    @Autowired
    TransactionTemplate transactionTemplate;

    @AfterEach
    void clear() {
        BillingStubs.clearCallsInTransaction();
    }

    @Test
    @DisplayName("DB 트랜잭션 안에서 부르면 기록하고, 밖에서 부르면 기록하지 않는다")
    void recordsCallsInTransaction() {
        HoldCommand command = new HoldCommand(1L, BillingReferenceType.APPEARANCE_REQUEST, 1L, 20_000);

        billingClient.hold(command);
        assertThat(BillingStubs.callsInTransaction()).isEmpty();

        transactionTemplate.executeWithoutResult(status -> billingClient.hold(command));
        assertThat(BillingStubs.callsInTransaction()).containsExactly("hold");
    }
}
