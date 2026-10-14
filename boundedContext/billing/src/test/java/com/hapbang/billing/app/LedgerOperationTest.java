package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.shared.billing.domain.BillingReferenceType;

// 원장 기록 요청(LedgerOperation·LedgerEntry)의 입력 검증·멱등키·버킷 이동 구성을 검증한다.
class LedgerOperationTest {

    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

    @Test
    @DisplayName("0 HP 원장 줄은 INVALID_POINT_AMOUNT로 거절한다")
    void zeroAmount() {
        assertThatThrownBy(() -> LedgerEntry.of(1L, PointType.CHARGE, BalanceBucket.AVAILABLE, 0L))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INVALID_POINT_AMOUNT));
    }

    @Test
    @DisplayName("버킷 이동은 출발 버킷 감소·도착 버킷 증가 두 줄이고 HP 총량은 변하지 않는다")
    void move() {
        List<LedgerEntry> entries = LedgerEntry.move(1L, PointType.CHARGE, BalanceBucket.AVAILABLE, BalanceBucket.HELD,
                10_000L);

        assertThat(entries).containsExactly(
                LedgerEntry.of(1L, PointType.CHARGE, BalanceBucket.AVAILABLE, -10_000L),
                LedgerEntry.of(1L, PointType.CHARGE, BalanceBucket.HELD, 10_000L));
        assertThat(entries.stream().mapToLong(LedgerEntry::amountHp).sum()).isZero();
    }

    @Test
    @DisplayName("버킷 이동 금액이 0 이하이거나 같은 버킷이면 거절한다")
    void move_invalid() {
        assertThatThrownBy(() -> LedgerEntry.move(1L, PointType.CHARGE, BalanceBucket.AVAILABLE, BalanceBucket.HELD, 0L))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INVALID_POINT_AMOUNT));
        assertThatThrownBy(() -> LedgerEntry.move(1L, PointType.CHARGE, BalanceBucket.HELD, BalanceBucket.HELD, 100L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("원장 멱등키는 작업 키와 줄 번호로 결정되고 락 대상 지갑은 중복 없이 오름차순이다")
    void idempotencyKeyAndWalletIds() {
        LedgerOperation operation = new LedgerOperation("HOLD:1001", LedgerTransactionType.HOLD,
                BillingReferenceType.LECTURE_ORDER, 1001L, null, NOW, List.of(
                LedgerEntry.of(3L, PointType.CHARGE, BalanceBucket.AVAILABLE, -100L),
                LedgerEntry.of(1L, PointType.CHARGE, BalanceBucket.HELD, 100L),
                LedgerEntry.of(3L, PointType.REVENUE, BalanceBucket.AVAILABLE, 5L)));

        assertThat(operation.idempotencyKey(1)).isEqualTo("HOLD:1001:1");
        assertThat(operation.idempotencyKey(2)).isEqualTo("HOLD:1001:2");
        assertThat(operation.walletIds()).containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("작업 키가 비었거나 원장 줄이 없거나 멱등키가 컬럼 길이를 넘으면 거절한다")
    void invalidOperation() {
        List<LedgerEntry> entries = List.of(LedgerEntry.of(1L, PointType.CHARGE, BalanceBucket.AVAILABLE, 100L));

        assertThatThrownBy(() -> operation(" ", entries)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operation("op", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operation("x".repeat(254), entries)).isInstanceOf(IllegalArgumentException.class);
        assertThat(operation("x".repeat(253), entries).idempotencyKey(1)).hasSize(255);
    }

    @Test
    @DisplayName("거래 유형 REVERSAL과 원본 원장은 함께 지정해야 하며, 반환(RELEASE) 등 다른 유형은 원본 원장을 가질 수 없다")
    void reversalTypeAndOriginalTogether() {
        assertThatThrownBy(() -> new LedgerOperation("REVERSAL:1", LedgerTransactionType.REVERSAL,
                BillingReferenceType.POINT_CHARGE, 1L, null, NOW,
                List.of(LedgerEntry.of(1L, PointType.CHARGE, BalanceBucket.AVAILABLE, -100L))))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INVALID_REVERSAL));
        assertThatThrownBy(() -> new LedgerOperation("RELEASE:1", LedgerTransactionType.RELEASE,
                BillingReferenceType.LECTURE_ORDER, 1L, null, NOW,
                List.of(new LedgerEntry(1L, PointType.CHARGE, BalanceBucket.AVAILABLE, 100L, 10L))))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INVALID_REVERSAL));
    }

    // 작업 키와 원장 줄로 충전 원장 기록 요청을 만든다.
    private LedgerOperation operation(String operationKey, List<LedgerEntry> entries) {
        return new LedgerOperation(operationKey, LedgerTransactionType.CHARGE, BillingReferenceType.POINT_CHARGE, 1L,
                null, NOW, entries);
    }
}
