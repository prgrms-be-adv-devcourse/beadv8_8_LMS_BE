package com.hapbang.billing.app;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.shared.billing.domain.BillingReferenceType;

// 하나의 비즈니스 작업(operationKey)으로 함께 기록할 원장 줄들과 공통 거래 정보를 담는 원장 기록 요청이다.
public record LedgerOperation(
        String operationKey,
        LedgerTransactionType transactionType,
        BillingReferenceType referenceType,
        Long referenceId,
        String description,
        Instant occurredAt,
        List<LedgerEntry> entries
) {

    // POINT_LEDGER.idempotency_key 컬럼 길이
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    // 필수 값과 원장 줄을 확인하고, 마지막 줄의 멱등키가 컬럼 길이를 넘지 않는지 검사한다.
    public LedgerOperation {
        if (operationKey == null || operationKey.isBlank()) {
            throw new IllegalArgumentException("작업 키는 비어 있을 수 없습니다.");
        }
        Objects.requireNonNull(transactionType, "거래 유형은 필수입니다.");
        Objects.requireNonNull(referenceType, "참조 유형은 필수입니다.");
        Objects.requireNonNull(referenceId, "참조 ID는 필수입니다.");
        Objects.requireNonNull(occurredAt, "발생 시각은 필수입니다.");
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("원장 줄이 비어 있습니다.");
        }
        entries = List.copyOf(entries);
        if ((operationKey + ":" + entries.size()).length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException("작업 키가 너무 깁니다.");
        }
        // 역분개(REVERSAL)는 모든 줄에 원본 원장이 있어야 하고, 다른 거래 유형의 줄은 원본 원장을 가질 수 없다.
        boolean reversal = transactionType == LedgerTransactionType.REVERSAL;
        if (entries.stream().anyMatch(entry -> (entry.originalLedgerId() != null) != reversal)) {
            throw new BillingException(BillingErrorCode.INVALID_REVERSAL);
        }
    }

    // 원장 줄 번호(1부터)로 결정론적인 원장 멱등키 {operationKey}:{lineNumber}를 만든다.
    public String idempotencyKey(int lineNumber) {
        return operationKey + ":" + lineNumber;
    }

    // 락을 걸 지갑 ID를 중복 없이 오름차순으로 반환한다.
    public SortedSet<Long> walletIds() {
        TreeSet<Long> walletIds = new TreeSet<>();
        entries.forEach(entry -> walletIds.add(entry.walletId()));
        return walletIds;
    }
}
