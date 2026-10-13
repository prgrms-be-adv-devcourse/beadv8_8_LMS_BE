package com.hapbang.billing.app;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.app.ChargeUsageHistory.UsageRelease;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.HoldDetailActionType;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointCharge;
import com.hapbang.billing.domain.PointChargeUsage;
import com.hapbang.billing.domain.PointHold;
import com.hapbang.billing.domain.PointHoldDetail;
import com.hapbang.billing.domain.PointHoldStatus;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.PointChargeRepository;
import com.hapbang.billing.out.PointChargeUsageRepository;
import com.hapbang.billing.out.PointHoldDetailRepository;
import com.hapbang.billing.out.PointHoldRepository;
import com.hapbang.billing.out.PointLedgerRepository;
import com.hapbang.billing.out.WalletRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;

// 참조 대상 보류의 남은 HP 일부 또는 전부를 결제한 회원에게 반환한다. 충전 HP를 먼저, 부족분은 수익 HP를 반환하고
// 충전 HP는 최초 배분의 역순으로 원래 충전 건의 가용 원금에 되돌린다. 보류·반환 상세·배분·충전 원금·원장·지갑 잔액을 한 트랜잭션에서 바꾼다.
@Service
@RequiredArgsConstructor
public class HoldReleaseUseCase {

    static final String DETAIL_IDEMPOTENCY_UNIQUE = "uk_point_hold_detail_idempotency_key";
    static final String UNIQUE_VIOLATION = "23505";

    private static final String DESCRIPTION = "HP 반환";

    private final WalletRepository walletRepository;
    private final PointHoldRepository pointHoldRepository;
    private final PointHoldDetailRepository pointHoldDetailRepository;
    private final PointChargeRepository pointChargeRepository;
    private final PointChargeUsageRepository pointChargeUsageRepository;
    private final PointLedgerRepository pointLedgerRepository;
    private final LedgerRecordUseCase ledgerRecordUseCase;
    private final EntityManager entityManager;

    // 지갑 락 → 보류 락·소유 확인 → 처리 이력 재생·대조 → (재요청이면 처음 결과) → 반환 배분 계산
    // → 충전 건 락·원금 대조·복원 → 보류·배분 변경 → 반환 상세 저장 → 원장 기록 순으로 처리한다.
    // 호출한 쪽 트랜잭션이 있으면 그 안에서 실행되며, 어느 단계든 실패하면 모든 변경이 롤백된다.
    @Transactional
    public ReleaseResult release(ReleaseCommand command) {
        // 금융 거래에서는 지갑을 자동 생성하지 않는다.
        Long walletId = walletRepository.findByUserId(command.userId())
                .map(Wallet::getId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.WALLET_NOT_FOUND));
        // 보류·배분·충전 원금은 소유 지갑의 락 아래에서만 바뀌므로, 이 락으로 같은 지갑의 보류·반환·지급이 직렬화된다.
        // lockWallet이 먼저 flush하므로 아래 refresh가 같은 트랜잭션의 미반영 변경을 지우지 않는다.
        Wallet wallet = ledgerRecordUseCase.lockWallet(walletId);
        PointHold hold = lockHold(command, walletId);

        String key = command.idempotencyKey(hold.getId());

        List<PointHoldDetail> details = pointHoldDetailRepository.findAllByPointHoldIdOrderByIdAsc(hold.getId());
        List<PointChargeUsage> usages = pointChargeUsageRepository.findAllByPointHoldIdOrderByIdAsc(hold.getId());
        usages.forEach(entityManager::refresh);
        ChargeUsageHistory history = verifiedHistory(hold, usages, details);

        Optional<PointHoldDetail> existing = details.stream()
                .filter(detail -> detail.getIdempotencyKey().equals(key))
                .findFirst();
        if (existing.isPresent()) {
            return replay(command, hold, existing.get(), history);
        }
        // 반환 상세 없이 원장만 있는 상태는 정상 재요청이 아니므로 새로 반환하지 않는다.
        if (!pointLedgerRepository.findAllByOperationKeyOrderByIdAsc(key).isEmpty()) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        // 이미 끝난 보류는 다른 새 요청으로 다시 반환하지 않는다. 전체 반환은 처음 처리할 때 남아 있는 보류액을 기준으로 한다.
        if (!hold.isOpen() || hold.heldTotalHp() == 0) {
            throw new BillingException(BillingErrorCode.HOLD_ALREADY_CLOSED);
        }
        long amountHp = command.isFull() ? hold.heldTotalHp() : command.amountHp();
        ReleaseSplit split = ReleaseSplit.of(hold.getHeldChargeHp(), hold.getHeldRevenueHp(), amountHp);

        List<UsageRelease> usageReleases = split.chargeHp() > 0 ? history.release(split.chargeHp()) : List.of();
        restoreCharges(wallet, usages, usageReleases);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        hold.release(split.chargeHp(), split.revenueHp(), hasPayout(details), now);
        PointHoldDetail detail = save(PointHoldDetail.released(hold, split.chargeHp(), split.revenueHp(), key));
        // record()는 시작할 때 flush하므로 위의 보류·배분·충전 원금·상세 변경도 원장보다 먼저 같은 트랜잭션에서 DB에 반영된다.
        // 지갑은 여기까지 바꾸지 않았으므로 record()의 refresh가 지울 변경이 없고, 잔액은 record()가 원장으로만 바꾼다.
        ledgerRecordUseCase.record(new LedgerOperation(key, LedgerTransactionType.RELEASE, hold.getReferenceType(),
                hold.getReferenceId(), DESCRIPTION, now, ledgerEntries(walletId, split)));
        return ReleaseResult.of(hold, detail, ChargeUsageHistory.toRestorations(usageReleases), false);
    }

    // 참조 대상의 보류를 찾아 요청 회원의 지갑 것인지 확인하고, 쓰기 락을 걸어 DB의 최신 상태로 다시 읽는다(지갑 → 보류 락 순서).
    private PointHold lockHold(ReleaseCommand command, Long walletId) {
        PointHold hold = pointHoldRepository.findByIdempotencyKey(command.holdKey())
                .orElseThrow(() -> new BillingException(BillingErrorCode.HOLD_NOT_FOUND));
        if (!hold.getWalletId().equals(walletId)) {
            throw new BillingException(BillingErrorCode.HOLD_OWNER_MISMATCH);
        }
        entityManager.refresh(hold, LockModeType.PESSIMISTIC_WRITE);
        return hold;
    }

    // 보류·배분·상세의 금액이 서로 맞는지 확인하고, 상세를 처리 순서대로 재생한 배분별 결과가 DB의 누적값과 같은지 대조한다.
    // 하나라도 다르면 처리 이력을 재구성할 수 없으므로 HOLD_STATE_INCONSISTENT로 거절한다.
    private ChargeUsageHistory verifiedHistory(PointHold hold, List<PointChargeUsage> usages,
            List<PointHoldDetail> details) {
        long allocatedHp = usages.stream().mapToLong(PointChargeUsage::getAmountHp).sum();
        long processedChargeHp = details.stream().mapToLong(PointHoldDetail::getChargeAmountHp).sum();
        long processedRevenueHp = details.stream().mapToLong(PointHoldDetail::getRevenueAmountHp).sum();
        if (allocatedHp != hold.getOriginalChargeHp()
                || processedChargeHp != hold.getOriginalChargeHp() - hold.getHeldChargeHp()
                || processedRevenueHp != hold.getOriginalRevenueHp() - hold.getHeldRevenueHp()
                || details.isEmpty() != (hold.getStatus() == PointHoldStatus.HELD)
                || hold.isOpen() != (hold.heldTotalHp() > 0)) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        ChargeUsageHistory history = ChargeUsageHistory.replay(
                usages.stream()
                        .map(usage -> new ChargeUsageHistory.Allocation(usage.getId(), usage.getChargeId(),
                                usage.getAmountHp()))
                        .toList(),
                details.stream()
                        .map(detail -> new ChargeUsageHistory.Processing(detail.getId(), detail.getActionType(),
                                detail.getChargeAmountHp()))
                        .toList());
        for (PointChargeUsage usage : usages) {
            if (!history.matches(usage.getId(), usage.getRemainingHeldHp(), usage.getConsumedHp(),
                    usage.getReleasedHp())) {
                throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
            }
        }
        return history;
    }

    // 이미 처리한 같은 요청이면 현재 보류 상태와 관계없이 다시 바꾸지 않고 그때의 반환 결과를 돌려준다.
    // 부분 반환 금액이 처음과 다르면 IDEMPOTENCY_KEY_CONFLICT, 원장 줄 수가 맞지 않으면 HOLD_STATE_INCONSISTENT로 거절한다.
    // 전체 반환 재요청은 금액을 비교하지 않고 처음 반환한 금액을 그대로 돌려준다(남은 보류를 다시 계산하지 않는다).
    private ReleaseResult replay(ReleaseCommand command, PointHold hold, PointHoldDetail detail,
            ChargeUsageHistory history) {
        if (detail.getActionType() != HoldDetailActionType.RELEASE
                || (!command.isFull() && detail.totalAmountHp() != command.amountHp())) {
            throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        int ledgerLines = pointLedgerRepository.findAllByOperationKeyOrderByIdAsc(detail.getIdempotencyKey()).size();
        int expectedLines = (detail.getChargeAmountHp() > 0 ? 2 : 0) + (detail.getRevenueAmountHp() > 0 ? 2 : 0);
        if (ledgerLines != expectedLines) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        return ReleaseResult.of(hold, detail, history.restorationsOf(detail.getId()), true);
    }

    // 반환할 충전 건을 ID 오름차순으로 잠그고(지갑 → 보류 → 충전 건), 원금 합계를 지갑 충전 HP 가용 잔액과 대조한 뒤
    // 배분별 남은 보류를 줄이고 원래 충전 건의 가용 원금을 늘린다. 합계가 다르거나 다른 지갑의 충전 건이면 CHARGE_PRINCIPAL_MISMATCH로 거절한다.
    private void restoreCharges(Wallet wallet, List<PointChargeUsage> usages, List<UsageRelease> usageReleases) {
        long principalHp = pointChargeRepository.sumRemainingPrincipal(wallet.getId(),
                HoldCreateUseCase.ALLOCATABLE_CHARGE_STATUSES);
        if (principalHp != wallet.getChargeBalanceHp()) {
            throw new BillingException(BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);
        }
        if (usageReleases.isEmpty()) {
            return;
        }
        TreeSet<Long> chargeIds = usageReleases.stream()
                .map(UsageRelease::chargeId)
                .collect(Collectors.toCollection(TreeSet::new));
        Map<Long, PointCharge> charges = new HashMap<>();
        for (PointCharge charge : pointChargeRepository.findAllByIdInForUpdate(chargeIds)) {
            // 락 조회는 이미 영속성 컨텍스트에 있던 충전 건의 상태를 바꾸지 않으므로 락을 잡은 뒤 최신 원금으로 다시 읽는다.
            entityManager.refresh(charge);
            if (!charge.getWalletId().equals(wallet.getId())) {
                throw new BillingException(BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);
            }
            charges.put(charge.getId(), charge);
        }
        Map<Long, PointChargeUsage> usagesById = usages.stream()
                .collect(Collectors.toMap(PointChargeUsage::getId, Function.identity()));
        for (UsageRelease usageRelease : usageReleases) {
            PointCharge charge = charges.get(usageRelease.chargeId());
            if (charge == null) {
                throw new BillingException(BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);
            }
            usagesById.get(usageRelease.usageId()).release(usageRelease.amountHp());
            charge.restorePrincipal(usageRelease.amountHp());
        }
    }

    // 이 보류에서 지급한 적이 있는지 확인한다. 남은 보류가 모두 처리될 때 RELEASED와 MIXED를 가른다.
    private static boolean hasPayout(List<PointHoldDetail> details) {
        return details.stream().anyMatch(detail -> detail.getActionType() == HoldDetailActionType.PAYOUT);
    }

    // 충전 HP 이동을 먼저, 수익 HP 이동을 다음 줄로 만든다(보류 → 가용). 금액이 0인 HP 종류는 원장 줄을 만들지 않는다.
    private static List<LedgerEntry> ledgerEntries(Long walletId, ReleaseSplit split) {
        List<LedgerEntry> entries = new ArrayList<>();
        if (split.chargeHp() > 0) {
            entries.addAll(LedgerEntry.move(walletId, PointType.CHARGE, BalanceBucket.HELD, BalanceBucket.AVAILABLE,
                    split.chargeHp()));
        }
        if (split.revenueHp() > 0) {
            entries.addAll(LedgerEntry.move(walletId, PointType.REVENUE, BalanceBucket.HELD, BalanceBucket.AVAILABLE,
                    split.revenueHp()));
        }
        return entries;
    }

    // 반환 상세를 INSERT하고, 같은 반환 키가 먼저 커밋되어 UNIQUE 충돌이 나면 IDEMPOTENCY_KEY_CONFLICT로 바꿔 전체를 롤백시킨다.
    private PointHoldDetail save(PointHoldDetail detail) {
        try {
            return pointHoldDetailRepository.save(detail);
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateDetail(exception)) {
                throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception);
            }
            throw exception;
        }
    }

    // 예외 원인의 PostgreSQL 오류가 UNIQUE 위반(23505)이고 서버가 보낸 제약 이름이 보류 상세 멱등키 UNIQUE인지 확인한다.
    static boolean isDuplicateDetail(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgresException) {
                ServerErrorMessage serverError = postgresException.getServerErrorMessage();
                return serverError != null
                        && UNIQUE_VIOLATION.equals(serverError.getSQLState())
                        && DETAIL_IDEMPOTENCY_UNIQUE.equals(serverError.getConstraint());
            }
        }
        return false;
    }
}
