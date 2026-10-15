package com.hapbang.billing.app;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointCharge;
import com.hapbang.billing.domain.PointChargeStatus;
import com.hapbang.billing.domain.PointChargeUsage;
import com.hapbang.billing.domain.PointHold;
import com.hapbang.billing.domain.PointHoldType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.PointChargeRepository;
import com.hapbang.billing.out.PointChargeUsageRepository;
import com.hapbang.billing.out.PointHoldRepository;
import com.hapbang.billing.out.PointLedgerRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;

import lombok.RequiredArgsConstructor;

// 참조 대상 하나의 최초 HP 보류를 만든다. 수익 HP를 먼저, 부족분은 충전 HP를 충전 건 FIFO로 배분하고
// 보류·충전 원금 배분·원장·지갑 잔액을 한 트랜잭션에서 바꾼다. 반환·지급은 하지 않는다.
@Service
@RequiredArgsConstructor
public class HoldCreateUseCase {

    static final String HOLD_IDEMPOTENCY_UNIQUE = "uk_point_hold_idempotency_key";
    static final String UNIQUE_VIOLATION = "23505";

    // 원금을 배분할 수 있는 충전 완료 건의 상태. 이 충전 건들의 가용 원금 합계가 지갑의 충전 HP 가용 잔액과 같아야 한다.
    static final Set<PointChargeStatus> ALLOCATABLE_CHARGE_STATUSES =
            EnumSet.of(PointChargeStatus.COMPLETED, PointChargeStatus.PARTIALLY_REFUNDED);

    private static final String DESCRIPTION = "HP 보류";

    private final WalletRepository walletRepository;
    private final PointHoldRepository pointHoldRepository;
    private final PointChargeRepository pointChargeRepository;
    private final PointChargeUsageRepository pointChargeUsageRepository;
    private final PointLedgerRepository pointLedgerRepository;
    private final LedgerRecordUseCase ledgerRecordUseCase;

    // 지갑 락 → 기존 원장·보류 확인 → 배분 계산 → 충전 건 락·원금 대조 → 보류 저장·FIFO 배분 → 원장 기록 순으로 처리한다.
    // 호출한 쪽 트랜잭션이 있으면 그 안에서 실행되며, 어느 단계든 실패하면 보류·배분·충전 원금·원장·지갑 변경이 모두 롤백된다.
    @Transactional
    public HoldResult hold(HoldCommand command) {
        BillingCommandValidator.verify(command);
        return hold(command.userId(), command.referenceType(), command.referenceId(), command.amountHp(),
                PointHoldType.MIXED);
    }

    // Billing 내부 보류(#241): 환불은 충전 HP만(CHARGE_ONLY), 환전은 수익 HP만(REVENUE_ONLY) 보류한다. 나머지 규칙(충전 원금 FIFO 배분,
    // 원장, 멱등성, 지갑 → 충전 건 락 순서)은 BillingPort.hold와 같다.
    @Transactional
    public HoldResult hold(InternalHoldCommand command) {
        BillingCommandValidator.requirePositive(command.amountHp());
        return hold(command.userId(), command.referenceType(), command.referenceId(), command.amountHp(),
                command.holdType());
    }

    private HoldResult hold(Long userId, BillingReferenceType referenceType, Long referenceId, long amountHp,
            PointHoldType holdType) {
        // 금융 거래에서는 지갑을 자동 생성하지 않는다.
        Long walletId = walletRepository.findByUserId(userId)
                .map(Wallet::getId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.WALLET_NOT_FOUND));
        // 같은 지갑의 보류·원장 기록은 이 락으로 직렬화되므로, 락 이후의 조회는 먼저 커밋된 보류와 원장을 본다.
        // 다른 지갑의 같은 참조 대상 보류는 이 락으로 막히지 않고, 보류 멱등키 UNIQUE가 최종으로 막는다.
        Wallet wallet = ledgerRecordUseCase.lockWallet(walletId);

        // READ COMMITTED에서는 SELECT마다 그 시점에 커밋된 데이터를 보므로 원장을 먼저, 보류를 나중에 읽는다.
        // 보류와 원장은 한 트랜잭션에서 함께 커밋되므로, 먼저 읽은 원장이 보이면 나중에 읽는 보류도 반드시 보인다.
        // 반대 순서면 두 조회 사이에 다른 지갑의 보류가 커밋될 때 원장만 보여 정상 경쟁을 불일치로 잘못 판단한다.
        String key = BillingKeys.hold(referenceType, referenceId);
        boolean ledgerExists = !pointLedgerRepository.findAllByOperationKeyOrderByIdAsc(key).isEmpty();
        Optional<PointHold> existing = pointHoldRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return replay(amountHp, holdType, wallet, existing.get());
        }
        // 보류 없이 원장만 있는 상태는 정상 재요청이 아니므로 새로 보류하지 않는다.
        if (ledgerExists) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }

        HoldSplit split = HoldSplit.of(holdType, wallet.getRevenueBalanceHp(), wallet.getChargeBalanceHp(), amountHp);
        // 지갑 락을 잡은 뒤에 충전 건을 잠근다(지갑 → 충전 건 순서).
        List<PointCharge> charges = pointChargeRepository.findAllocatableForUpdate(walletId,
                ALLOCATABLE_CHARGE_STATUSES);
        verifyChargePrincipal(wallet, charges);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        PointHold hold = save(PointHold.held(walletId, referenceType, referenceId, split.chargeHp(),
                split.revenueHp(), key, now, holdType));
        List<HoldResult.ChargeAllocation> allocations = allocate(hold, charges, split.chargeHp());
        // record()는 시작할 때 flush하므로 위의 보류·배분·충전 원금 변경도 원장보다 먼저 같은 트랜잭션에서 DB에 반영된다.
        // 지갑은 여기까지 바꾸지 않았으므로 record()의 refresh가 지울 변경이 없고, 잔액은 record()가 원장으로만 바꾼다.
        ledgerRecordUseCase.record(new LedgerOperation(key, LedgerTransactionType.HOLD, referenceType,
                referenceId, DESCRIPTION, now, ledgerEntries(walletId, split)));
        return HoldResult.of(hold, allocations, false);
    }

    // 이미 있는 보류가 같은 지갑·같은 금액이면 다시 바꾸지 않고 처음 결과를 돌려주고, 다르면 IDEMPOTENCY_KEY_CONFLICT로 거절한다.
    // 보류는 있는데 원장 줄 수나 충전 원금 배분 합계가 맞지 않으면 HOLD_STATE_INCONSISTENT로 거절한다.
    // 보류 종류가 다른 재요청(같은 참조 대상)도 다른 내용이므로 IDEMPOTENCY_KEY_CONFLICT다.
    private HoldResult replay(long amountHp, PointHoldType holdType, Wallet wallet, PointHold hold) {
        if (!hold.getWalletId().equals(wallet.getId()) || hold.originalTotalHp() != amountHp
                || hold.getHoldType() != holdType) {
            throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        List<PointChargeUsage> usages = pointChargeUsageRepository.findAllByPointHoldIdOrderByIdAsc(hold.getId());
        long allocatedHp = usages.stream().mapToLong(PointChargeUsage::getAmountHp).sum();
        int ledgerLines = pointLedgerRepository.findAllByOperationKeyOrderByIdAsc(hold.getIdempotencyKey()).size();
        if (allocatedHp != hold.getOriginalChargeHp() || ledgerLines != expectedLedgerLines(hold)) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        List<HoldResult.ChargeAllocation> allocations = usages.stream()
                .map(usage -> new HoldResult.ChargeAllocation(usage.getChargeId(), usage.getAmountHp()))
                .toList();
        return HoldResult.of(hold, allocations, true);
    }

    // 배분 가능한 충전 건의 가용 원금 합계가 지갑의 충전 HP 가용 잔액과 다르면 CHARGE_PRINCIPAL_MISMATCH로 거절한다.
    private static void verifyChargePrincipal(Wallet wallet, List<PointCharge> charges) {
        long principalHp = 0;
        for (PointCharge charge : charges) {
            principalHp = Math.addExact(principalHp, charge.getRemainingPrincipalHp());
        }
        if (principalHp != wallet.getChargeBalanceHp()) {
            throw new BillingException(BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);
        }
    }

    // 충전 HP 보류액을 FIFO 순서의 충전 건에 앞에서부터 채워 배분하고, 충전 건마다 가용 원금을 줄이고 배분 내역(POINT_CHARGE_USAGE)을 남긴다.
    private List<HoldResult.ChargeAllocation> allocate(PointHold hold, List<PointCharge> charges, long chargeHp) {
        List<HoldResult.ChargeAllocation> allocations = new ArrayList<>();
        long remainingHp = chargeHp;
        for (PointCharge charge : charges) {
            if (remainingHp == 0) {
                break;
            }
            long amountHp = Math.min(remainingHp, charge.getRemainingPrincipalHp());
            charge.reservePrincipal(amountHp);
            pointChargeUsageRepository.save(PointChargeUsage.held(charge.getId(), hold, amountHp));
            allocations.add(new HoldResult.ChargeAllocation(charge.getId(), amountHp));
            remainingHp -= amountHp;
        }
        // 원금 합계를 먼저 대조했으므로 여기서 남으면 배분 로직의 오류다.
        if (remainingHp != 0) {
            throw new BillingException(BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);
        }
        return allocations;
    }

    // 수익 HP 이동을 먼저, 충전 HP 이동을 다음 줄로 만든다. 금액이 0인 HP 종류는 원장 줄을 만들지 않는다.
    private static List<LedgerEntry> ledgerEntries(Long walletId, HoldSplit split) {
        List<LedgerEntry> entries = new ArrayList<>();
        if (split.revenueHp() > 0) {
            entries.addAll(LedgerEntry.move(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, BalanceBucket.HELD,
                    split.revenueHp()));
        }
        if (split.chargeHp() > 0) {
            entries.addAll(LedgerEntry.move(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, BalanceBucket.HELD,
                    split.chargeHp()));
        }
        return entries;
    }

    // 보류의 HP 종류별 최초 보류액으로 기록되어 있어야 할 원장 줄 수(이동 한 건당 2줄)를 구한다.
    private static int expectedLedgerLines(PointHold hold) {
        return (hold.getOriginalRevenueHp() > 0 ? 2 : 0) + (hold.getOriginalChargeHp() > 0 ? 2 : 0);
    }

    // 보류를 INSERT하고, 조회 이후 다른 지갑의 같은 참조 대상 보류가 먼저 커밋되어(또는 커밋 전 INSERT를 기다린 뒤) 멱등키 UNIQUE 충돌이 나면 IDEMPOTENCY_KEY_CONFLICT로 바꿔 전체를 롤백시킨다.
    private PointHold save(PointHold hold) {
        try {
            return pointHoldRepository.save(hold);
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateHold(exception)) {
                throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception);
            }
            throw exception;
        }
    }

    // 예외 원인의 PostgreSQL 오류가 UNIQUE 위반(23505)이고 서버가 보낸 제약 이름이 보류 멱등키 UNIQUE인지 확인한다.
    static boolean isDuplicateHold(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgresException) {
                ServerErrorMessage serverError = postgresException.getServerErrorMessage();
                return serverError != null
                        && UNIQUE_VIOLATION.equals(serverError.getSQLState())
                        && HOLD_IDEMPOTENCY_UNIQUE.equals(serverError.getConstraint());
            }
        }
        return false;
    }
}
