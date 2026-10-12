package com.hapbang.billing.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointLedger;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.PointLedgerRepository;
import com.hapbang.billing.out.WalletRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

// 원장 기록과 지갑 캐시 잔액 변경을 한 트랜잭션에서 함께 처리하는 Billing 내부 금융 처리 기반이다.
// 지갑 잔액은 이 UseCase가 저장하는 원장 항목으로만 바뀐다. 금융 거래에서는 지갑을 자동 생성하지 않는다.
@Service
@RequiredArgsConstructor
public class LedgerRecordUseCase {

    static final String LEDGER_IDEMPOTENCY_UNIQUE = "uk_point_ledger_idempotency_key";
    static final String UNIQUE_VIOLATION = "23505";

    private final WalletRepository walletRepository;
    private final PointLedgerRepository pointLedgerRepository;
    private final EntityManager entityManager;

    // 지갑을 ID 오름차순으로 잠근 뒤, 같은 작업이 이미 기록됐으면 재사용하고 아니면 원장 기록과 잔액 변경을 함께 반영한다.
    // 호출한 쪽 트랜잭션이 있으면 그 안에서, 없으면 새 트랜잭션에서 실행되며 어느 단계든 실패하면 전부 롤백된다.
    @Transactional
    public LedgerOperationResult record(LedgerOperation operation) {
        Map<Long, Wallet> wallets = lockWallets(operation);

        // 같은 지갑을 쓰는 같은 작업은 위 락으로 직렬화되므로, 락을 얻은 뒤의 조회는 먼저 커밋된 기록을 본다.
        List<PointLedger> recorded = pointLedgerRepository.findAllByOperationKeyOrderByIdAsc(operation.operationKey());
        if (!recorded.isEmpty()) {
            return replay(operation, recorded);
        }

        List<Long> ledgerIds = new ArrayList<>();
        Map<Long, Long> reversedInOperation = new HashMap<>();
        List<LedgerEntry> entries = operation.entries();
        for (int line = 1; line <= entries.size(); line++) {
            LedgerEntry entry = entries.get(line - 1);
            Wallet wallet = wallets.get(entry.walletId());
            PointLedger ledger = PointLedger.builder()
                    .wallet(wallet)
                    .pointType(entry.pointType())
                    .balanceBucket(entry.balanceBucket())
                    .amountHp(entry.amountHp())
                    .transactionType(operation.transactionType())
                    .referenceType(operation.referenceType())
                    .referenceId(operation.referenceId())
                    .operationKey(operation.operationKey())
                    .originalLedger(reversalTarget(entry, reversedInOperation))
                    .idempotencyKey(operation.idempotencyKey(line))
                    .description(operation.description())
                    .occurredAt(operation.occurredAt())
                    .build();
            wallet.applyLedger(ledger);
            ledgerIds.add(save(ledger).getId());
        }
        return new LedgerOperationResult(operation.operationKey(), false, ledgerIds);
    }

    // 요청의 지갑을 ID 오름차순으로 하나씩 PESSIMISTIC_WRITE로 잠그고 DB의 최신 잔액으로 다시 읽으며, 없는 지갑은 만들지 않고 WALLET_NOT_FOUND로 거절한다.
    private Map<Long, Wallet> lockWallets(LedgerOperation operation) {
        // 같은 트랜잭션에서 앞서 바꾼 지갑 잔액을 먼저 DB에 반영해야 아래 refresh가 그 변경을 덮어쓰지 않는다.
        entityManager.flush();
        Map<Long, Wallet> wallets = new LinkedHashMap<>();
        for (Long walletId : operation.walletIds()) {
            Wallet wallet = walletRepository.findByIdForUpdate(walletId)
                    .orElseThrow(() -> new BillingException(BillingErrorCode.WALLET_NOT_FOUND));
            // 락 조회는 이미 영속성 컨텍스트에 있던 지갑의 상태를 바꾸지 않으므로, 락을 잡은 뒤 최신 잔액으로 다시 읽는다.
            entityManager.refresh(wallet);
            wallets.put(walletId, wallet);
        }
        return wallets;
    }

    // 이미 기록된 같은 작업이 요청과 줄마다 같으면 잔액을 다시 바꾸지 않고 기존 원장 ID를 돌려주고, 다르면 IDEMPOTENCY_KEY_CONFLICT로 거절한다.
    private LedgerOperationResult replay(LedgerOperation operation, List<PointLedger> recorded) {
        List<LedgerEntry> entries = operation.entries();
        if (recorded.size() != entries.size()) {
            throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        List<Long> ledgerIds = new ArrayList<>();
        for (int line = 1; line <= entries.size(); line++) {
            PointLedger ledger = recorded.get(line - 1);
            if (!isSameLine(operation, line, entries.get(line - 1), ledger)) {
                throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            ledgerIds.add(ledger.getId());
        }
        return new LedgerOperationResult(operation.operationKey(), true, ledgerIds);
    }

    // 기록된 원장 줄이 요청 줄과 멱등키·지갑·HP 종류·버킷·금액·거래 유형·참조 대상·역분개 원본까지 같은지 확인한다(발생 시각·설명은 비교하지 않는다).
    private boolean isSameLine(LedgerOperation operation, int line, LedgerEntry entry, PointLedger ledger) {
        Long originalLedgerId = ledger.getOriginalLedger() == null ? null : ledger.getOriginalLedger().getId();
        return operation.idempotencyKey(line).equals(ledger.getIdempotencyKey())
                && entry.walletId().equals(ledger.getWallet().getId())
                && entry.pointType() == ledger.getPointType()
                && entry.balanceBucket() == ledger.getBalanceBucket()
                && entry.amountHp() == ledger.getAmountHp()
                && operation.transactionType() == ledger.getTransactionType()
                && operation.referenceType() == ledger.getReferenceType()
                && operation.referenceId().equals(ledger.getReferenceId())
                && Objects.equals(entry.originalLedgerId(), originalLedgerId);
    }

    // 역분개 줄이면 원본 원장을 조회해 지갑·HP 종류·버킷·반대 부호·누적 한도를 검증하고, 맞지 않으면 INVALID_REVERSAL로 거절한다.
    private PointLedger reversalTarget(LedgerEntry entry, Map<Long, Long> reversedInOperation) {
        if (entry.originalLedgerId() == null) {
            return null;
        }
        PointLedger original = pointLedgerRepository.findById(entry.originalLedgerId())
                .orElseThrow(() -> new BillingException(BillingErrorCode.ORIGINAL_LEDGER_NOT_FOUND));
        if (original.getTransactionType() == LedgerTransactionType.REVERSAL
                || !entry.walletId().equals(original.getWallet().getId())
                || entry.pointType() != original.getPointType()
                || entry.balanceBucket() != original.getBalanceBucket()
                || Long.signum(entry.amountHp()) == Long.signum(original.getAmountHp())) {
            throw new BillingException(BillingErrorCode.INVALID_REVERSAL);
        }
        // 역분개 지갑은 원본 지갑과 같아 이미 잠겨 있으므로, 같은 원본의 다른 역분개가 이 사이에 커밋되지 않는다. 같은 작업의 앞 줄도 함께 센다.
        long reversed = reversedInOperation.computeIfAbsent(original.getId(),
                pointLedgerRepository::sumAmountByOriginalLedgerId);
        long total = Math.addExact(reversed, entry.amountHp());
        if (Math.absExact(total) > Math.absExact(original.getAmountHp())) {
            throw new BillingException(BillingErrorCode.INVALID_REVERSAL);
        }
        reversedInOperation.put(original.getId(), total);
        return original;
    }

    // 원장을 INSERT하고, 다른 요청이 같은 멱등키를 먼저 기록해 UNIQUE 충돌이 나면 IDEMPOTENCY_KEY_CONFLICT로 바꿔 전체를 롤백시킨다.
    private PointLedger save(PointLedger ledger) {
        try {
            return pointLedgerRepository.save(ledger);
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateIdempotencyKey(exception)) {
                throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception);
            }
            throw exception;
        }
    }

    // 예외 원인의 PostgreSQL 오류가 UNIQUE 위반(23505)이고 서버가 보낸 제약 이름이 원장 멱등키 UNIQUE인지 확인한다.
    static boolean isDuplicateIdempotencyKey(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgresException) {
                ServerErrorMessage serverError = postgresException.getServerErrorMessage();
                return serverError != null
                        && UNIQUE_VIOLATION.equals(serverError.getSQLState())
                        && LEDGER_IDEMPOTENCY_UNIQUE.equals(serverError.getConstraint());
            }
        }
        return false;
    }
}
