package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BillingReferenceType;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointLedger;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.PointLedgerRepository;
import com.hapbang.billing.out.WalletRepository;

import jakarta.persistence.EntityManager;

// 원장 기록 UseCase의 지갑 락 순서와 지갑 미존재 처리를 DB 없이 검증한다.
class LedgerRecordUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

    WalletRepository walletRepository = mock(WalletRepository.class);
    PointLedgerRepository pointLedgerRepository = mock(PointLedgerRepository.class);
    EntityManager entityManager = mock(EntityManager.class);
    LedgerRecordUseCase useCase = new LedgerRecordUseCase(walletRepository, pointLedgerRepository, entityManager);

    // 저장한 원장에 차례로 ID를 붙여 돌려주도록 저장소 목을 준비한다.
    @BeforeEach
    void setUp() {
        AtomicLong sequence = new AtomicLong();
        given(pointLedgerRepository.findAllByOperationKeyOrderByIdAsc(any())).willReturn(List.of());
        given(pointLedgerRepository.save(any())).willAnswer(invocation -> {
            PointLedger ledger = invocation.getArgument(0);
            ReflectionTestUtils.setField(ledger, "id", sequence.incrementAndGet());
            return ledger;
        });
    }

    @Test
    @DisplayName("미반영 변경을 먼저 flush하고, 원장 줄 순서와 관계없이 지갑을 ID 오름차순으로 잠근 뒤 최신 상태로 다시 읽는다")
    void locksWalletsInIdOrder() {
        Wallet first = Wallet.create(10L);
        Wallet second = Wallet.create(20L);
        given(walletRepository.findByIdForUpdate(1L)).willReturn(Optional.of(first));
        given(walletRepository.findByIdForUpdate(2L)).willReturn(Optional.of(second));

        useCase.record(operation(
                LedgerEntry.of(2L, PointType.CHARGE, BalanceBucket.AVAILABLE, 100L),
                LedgerEntry.of(1L, PointType.REVENUE, BalanceBucket.AVAILABLE, 100L)));

        InOrder order = inOrder(entityManager, walletRepository, pointLedgerRepository);
        order.verify(entityManager).flush();
        order.verify(walletRepository).findByIdForUpdate(1L);
        order.verify(entityManager).refresh(first);
        order.verify(walletRepository).findByIdForUpdate(2L);
        order.verify(entityManager).refresh(second);
        order.verify(pointLedgerRepository).findAllByOperationKeyOrderByIdAsc("op");
        order.verify(pointLedgerRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("지갑이 없으면 만들지 않고 WALLET_NOT_FOUND로 거절하며 원장을 저장하지 않는다")
    void walletNotFound() {
        given(walletRepository.findByIdForUpdate(1L)).willReturn(Optional.of(Wallet.create(10L)));
        given(walletRepository.findByIdForUpdate(2L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.record(operation(
                LedgerEntry.of(1L, PointType.CHARGE, BalanceBucket.AVAILABLE, 100L),
                LedgerEntry.of(2L, PointType.CHARGE, BalanceBucket.AVAILABLE, 100L))))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.WALLET_NOT_FOUND));
        verify(walletRepository, never()).save(any());
        verify(pointLedgerRepository, never()).save(any());
    }

    // 작업 키 op의 충전 원장 기록 요청을 만든다.
    private LedgerOperation operation(LedgerEntry... entries) {
        return new LedgerOperation("op", LedgerTransactionType.CHARGE, BillingReferenceType.POINT_CHARGE, 1L, null,
                NOW, List.of(entries));
    }
}
