package com.hapbang.billing.app;

import java.time.Instant;
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
import org.springframework.stereotype.Component;

import com.hapbang.billing.app.ChargeUsageHistory.UsageConsumption;
import com.hapbang.billing.app.ChargeUsageHistory.UsageRelease;
import com.hapbang.billing.app.PayoutResult.ChargeConsumption;
import com.hapbang.billing.app.ReleaseResult.ChargeRestoration;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BillingReferenceType;
import com.hapbang.billing.domain.HoldDetailActionType;
import com.hapbang.billing.domain.PointCharge;
import com.hapbang.billing.domain.PointChargeUsage;
import com.hapbang.billing.domain.PointHold;
import com.hapbang.billing.domain.PointHoldDetail;
import com.hapbang.billing.domain.PointHoldStatus;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Settlement;
import com.hapbang.billing.domain.SettlementSourceType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.PointChargeRepository;
import com.hapbang.billing.out.PointChargeUsageRepository;
import com.hapbang.billing.out.PointHoldDetailRepository;
import com.hapbang.billing.out.PointHoldRepository;
import com.hapbang.billing.out.PointLedgerRepository;
import com.hapbang.billing.out.SettlementRepository;
import com.hapbang.billing.out.WalletRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;

// 보류 반환·지급·위약금이 함께 쓰는 보류 처리 단계다. 트랜잭션을 열지 않으며, 호출한 UseCase가 관련 지갑을 모두
// (ID 오름차순으로) 잠근 트랜잭션 안에서만 쓴다. 그래서 지갑 → 보류 → 충전 건 락 순서가 UseCase마다 같게 유지된다.
// 원장 기록과 지갑 잔액 변경은 하지 않고, 호출한 UseCase가 LedgerRecordUseCase로 한다.
@Component
@RequiredArgsConstructor
class HoldProcessing {

    static final String UNIQUE_VIOLATION = "23505";
    static final String DETAIL_IDEMPOTENCY_UNIQUE = "uk_point_hold_detail_idempotency_key";
    static final String SETTLEMENT_IDEMPOTENCY_UNIQUE = "uk_settlement_idempotency_key";
    static final String SETTLEMENT_HOLD_SOURCE_UNIQUE = "uk_settlement_point_hold_id_source_type_source_id_payout_type";
    static final String SETTLEMENT_SOURCE_UNIQUE = "uk_settlement_source_type_source_id";

    private final WalletRepository walletRepository;
    private final PointHoldRepository pointHoldRepository;
    private final PointHoldDetailRepository pointHoldDetailRepository;
    private final PointChargeRepository pointChargeRepository;
    private final PointChargeUsageRepository pointChargeUsageRepository;
    private final PointLedgerRepository pointLedgerRepository;
    private final SettlementRepository settlementRepository;
    private final EntityManager entityManager;

    // 회원의 지갑 ID를 찾는다. 금융 거래에서는 지갑을 자동 생성하지 않으므로 없으면 WALLET_NOT_FOUND로 거절한다.
    Long walletIdOf(Long userId) {
        return walletRepository.findByUserId(userId)
                .map(Wallet::getId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.WALLET_NOT_FOUND));
    }

    // 참조 대상의 보류를 찾아 소유 지갑을 확인하고, 쓰기 락을 걸어 DB의 최신 상태로 다시 읽는다(지갑 → 보류 락 순서).
    // 소유 지갑의 락을 먼저 잡은 뒤에만 호출한다.
    PointHold lockHold(String holdKey, Long ownerWalletId) {
        PointHold hold = pointHoldRepository.findByIdempotencyKey(holdKey)
                .orElseThrow(() -> new BillingException(BillingErrorCode.HOLD_NOT_FOUND));
        if (!hold.getWalletId().equals(ownerWalletId)) {
            throw new BillingException(BillingErrorCode.HOLD_OWNER_MISMATCH);
        }
        entityManager.refresh(hold, LockModeType.PESSIMISTIC_WRITE);
        return hold;
    }

    // 보류의 상세·배분을 읽어(배분은 최신 상태로 refresh) 금액과 처리 이력을 대조한 처리 상태를 만든다.
    HoldState load(PointHold hold) {
        List<PointHoldDetail> details = pointHoldDetailRepository.findAllByPointHoldIdOrderByIdAsc(hold.getId());
        List<PointChargeUsage> usages = pointChargeUsageRepository.findAllByPointHoldIdOrderByIdAsc(hold.getId());
        usages.forEach(entityManager::refresh);
        return new HoldState(hold, new ArrayList<>(details), usages, verifiedHistory(hold, usages, details));
    }

    // 보류·배분·상세의 금액이 서로 맞는지 확인하고, 상세를 처리 순서(ID 순)대로 반환·지급 규칙으로 재생한 배분별 결과가
    // DB의 누적값(남은 보류·소진·반환)과 같은지 대조한다. 하나라도 다르면 처리 이력을 재구성할 수 없으므로 HOLD_STATE_INCONSISTENT로 거절한다.
    private static ChargeUsageHistory verifiedHistory(PointHold hold, List<PointChargeUsage> usages,
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

    // 보류 소유 지갑의 배분 가능 충전 원금 합계가 지갑의 충전 HP 가용 잔액과 다르면 CHARGE_PRINCIPAL_MISMATCH로 거절한다.
    void verifyChargePrincipal(Wallet owner) {
        long principalHp = pointChargeRepository.sumRemainingPrincipal(owner.getId(),
                HoldCreateUseCase.ALLOCATABLE_CHARGE_STATUSES);
        if (principalHp != owner.getChargeBalanceHp()) {
            throw new BillingException(BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);
        }
    }

    // 반환 한 건을 적용한다: 충전 HP를 배분 역순으로 반환하고 원래 충전 건(ID 오름차순으로 잠금)의 가용 원금에 되돌린 뒤,
    // 보류를 줄이고 반환 상세를 저장한다. 원장은 기록하지 않는다.
    ReleaseApplied release(HoldState state, Wallet owner, ReleaseSplit split, String detailKey, Instant releasedAt) {
        List<UsageRelease> usageReleases = split.chargeHp() > 0
                ? state.history().release(split.chargeHp()) : List.of();
        restoreCharges(owner, state.usages(), usageReleases);
        state.hold().release(split.chargeHp(), split.revenueHp(), state.has(HoldDetailActionType.PAYOUT), releasedAt);
        PointHoldDetail detail = saveDetail(
                PointHoldDetail.released(state.hold(), split.chargeHp(), split.revenueHp(), detailKey));
        state.details().add(detail);
        return new ReleaseApplied(detail, ChargeUsageHistory.toRestorations(usageReleases));
    }

    // 지급 한 건을 적용한다: 충전 HP를 배분 순서(FIFO)로 소진하고(충전 건 원금은 되돌리지 않는다), 보류를 줄이고 지급 상세를 저장한다.
    // 원장·지급 건(Settlement)은 기록하지 않는다.
    PayoutApplied payout(HoldState state, PayoutSplit split, String detailKey) {
        List<UsageConsumption> consumptions = split.chargeHp() > 0
                ? state.history().consume(split.chargeHp()) : List.of();
        Map<Long, PointChargeUsage> usagesById = byId(state.usages());
        for (UsageConsumption consumption : consumptions) {
            usagesById.get(consumption.usageId()).consume(consumption.amountHp());
        }
        state.hold().payout(split.chargeHp(), split.revenueHp(), state.has(HoldDetailActionType.RELEASE));
        PointHoldDetail detail = saveDetail(
                PointHoldDetail.paidOut(state.hold(), split.chargeHp(), split.revenueHp(), detailKey));
        state.details().add(detail);
        return new PayoutApplied(detail, ChargeUsageHistory.toConsumptions(consumptions));
    }

    // 반환할 충전 건을 ID 오름차순으로 잠그고(지갑 → 보류 → 충전 건) 배분별 남은 보류를 줄이고 원래 충전 건의 가용 원금을 늘린다.
    // 다른 지갑의 충전 건이면 CHARGE_PRINCIPAL_MISMATCH로 거절한다.
    private void restoreCharges(Wallet owner, List<PointChargeUsage> usages, List<UsageRelease> usageReleases) {
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
            if (!charge.getWalletId().equals(owner.getId())) {
                throw new BillingException(BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);
            }
            charges.put(charge.getId(), charge);
        }
        Map<Long, PointChargeUsage> usagesById = byId(usages);
        for (UsageRelease usageRelease : usageReleases) {
            PointCharge charge = charges.get(usageRelease.chargeId());
            if (charge == null) {
                throw new BillingException(BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);
            }
            usagesById.get(usageRelease.usageId()).release(usageRelease.amountHp());
            charge.restorePrincipal(usageRelease.amountHp());
        }
    }

    private static Map<Long, PointChargeUsage> byId(List<PointChargeUsage> usages) {
        return usages.stream().collect(Collectors.toMap(PointChargeUsage::getId, Function.identity()));
    }

    // 작업 키로 기록된 원장 줄 수를 센다.
    int ledgerLines(String operationKey) {
        return pointLedgerRepository.findAllByOperationKeyOrderByIdAsc(operationKey).size();
    }

    // 멱등키로 지급 건을 찾는다.
    Optional<Settlement> settlement(String idempotencyKey) {
        return settlementRepository.findByIdempotencyKey(idempotencyKey);
    }

    // 지급 원천(출연 계약·강의 주문 항목)은 지급 유형과 관계없이 한 번만 정산한다. 한 출연 계약의 정상 지급과 위약금은 함께 성립할 수 없다.
    // - 같은 원천의 지급이 어느 보류에든 이미 있으면 PAYOUT_ALREADY_SETTLED로 거절한다(같은 계약 ID가 다른 보류로 다시 오는 경우 포함).
    // - 출연 요청(1:1) 보류는 출연 계약이 하나뿐이므로 원천이 달라도 지급 건이 이미 있으면 거절한다.
    // 이 확인은 일찍 거절하기 위한 것이고, 서로 다른 지갑의 보류가 같은 원천을 동시에 지급하는 경우는 DB UNIQUE(source_type, source_id)가
    // 늦게 INSERT하는 쪽을 막는다(saveSettlement가 PAYOUT_ALREADY_SETTLED로 바꾼다).
    void rejectSettledSource(PointHold hold, SettlementSourceType sourceType, Long sourceId) {
        boolean settled = settlementRepository.existsBySourceTypeAndSourceId(sourceType, sourceId)
                || (hold.getReferenceType() == BillingReferenceType.APPEARANCE_REQUEST
                        && settlementRepository.existsByPointHoldId(hold.getId()));
        if (settled) {
            throw new BillingException(BillingErrorCode.PAYOUT_ALREADY_SETTLED);
        }
    }

    // 지급 원천 유형이 보류의 거래 대상과 맞는지 확인한다. 출연 요청 보류는 그 요청에서 성립한 출연 계약(APPEARANCE_REQUEST, 출연 계약 ID),
    // 출연 모집 보류는 게스트별 출연 계약(RECRUIT_APPEARANCE, 출연 ID), 강의 주문 항목 보류는 그 항목(LECTURE_ORDER_ITEM, 같은 항목 ID)에
    // 지급한다. 출연 계약 ID가 그 보류에 속하는지는 Billing이 알 수 없어 출연이 검증한다. 맞지 않으면 PAYOUT_SOURCE_MISMATCH로 거절한다.
    static void verifySource(PointHold hold, SettlementSourceType sourceType, Long sourceId) {
        boolean matched = switch (hold.getReferenceType()) {
            case APPEARANCE_REQUEST -> sourceType == SettlementSourceType.APPEARANCE_REQUEST;
            case LECTURE_ORDER_ITEM -> sourceType == SettlementSourceType.LECTURE_ORDER_ITEM
                    && sourceId.equals(hold.getReferenceId());
            case APPEARANCE_RECRUIT -> sourceType == SettlementSourceType.RECRUIT_APPEARANCE;
            default -> false;
        };
        if (!matched) {
            throw new BillingException(BillingErrorCode.PAYOUT_SOURCE_MISMATCH);
        }
    }

    // 지급 건을 INSERT한다. 같은 멱등키가 먼저 커밋되면 IDEMPOTENCY_KEY_CONFLICT, 같은 원천의 지급이 먼저 커밋되면(다른 호스트의 보류가
    // 같은 출연 계약을 동시에 지급한 경우 등) PAYOUT_ALREADY_SETTLED로 바꿔 전체(보류·배분·상세·원장·지갑)를 롤백시킨다.
    Settlement saveSettlement(Settlement settlement) {
        try {
            return settlementRepository.save(settlement);
        } catch (DataIntegrityViolationException exception) {
            String constraint = uniqueViolation(exception);
            if (SETTLEMENT_IDEMPOTENCY_UNIQUE.equals(constraint)) {
                throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception);
            }
            if (SETTLEMENT_SOURCE_UNIQUE.equals(constraint) || SETTLEMENT_HOLD_SOURCE_UNIQUE.equals(constraint)) {
                throw new BillingException(BillingErrorCode.PAYOUT_ALREADY_SETTLED, exception);
            }
            throw exception;
        }
    }

    // 보류 상세를 INSERT하고, 같은 키가 먼저 커밋되어 UNIQUE 충돌이 나면 IDEMPOTENCY_KEY_CONFLICT로 바꿔 전체를 롤백시킨다.
    private PointHoldDetail saveDetail(PointHoldDetail detail) {
        try {
            return pointHoldDetailRepository.save(detail);
        } catch (DataIntegrityViolationException exception) {
            if (DETAIL_IDEMPOTENCY_UNIQUE.equals(uniqueViolation(exception))) {
                throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception);
            }
            throw exception;
        }
    }

    // 예외 원인의 PostgreSQL 오류가 UNIQUE 위반(23505)이면 서버가 보낸 제약 이름을, 아니면 null을 돌려준다.
    static String uniqueViolation(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgresException) {
                ServerErrorMessage serverError = postgresException.getServerErrorMessage();
                return serverError != null && UNIQUE_VIOLATION.equals(serverError.getSQLState())
                        ? serverError.getConstraint() : null;
            }
        }
        return null;
    }

    // 반환 원장 줄: 충전 HP 이동을 먼저, 수익 HP 이동을 다음 줄로 만든다(보류 → 가용). 금액이 0인 HP 종류는 줄을 만들지 않는다.
    static List<LedgerEntry> releaseEntries(Long walletId, ReleaseSplit split) {
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

    // 지급 원장 줄: 보류한 지갑의 수익 HP 보류·충전 HP 보류를 줄이고, 받는 지갑의 수익 HP 가용 잔액을 같은 총액만큼 늘린다.
    // 출처가 충전 HP여도 받는 쪽에는 수익 HP로 들어간다. 금액이 0인 HP 종류는 줄을 만들지 않는다.
    static List<LedgerEntry> payoutEntries(Long payerWalletId, Long receiverWalletId, PayoutSplit split) {
        List<LedgerEntry> entries = new ArrayList<>();
        if (split.revenueHp() > 0) {
            entries.add(LedgerEntry.of(payerWalletId, PointType.REVENUE, BalanceBucket.HELD, -split.revenueHp()));
        }
        if (split.chargeHp() > 0) {
            entries.add(LedgerEntry.of(payerWalletId, PointType.CHARGE, BalanceBucket.HELD, -split.chargeHp()));
        }
        entries.add(LedgerEntry.of(receiverWalletId, PointType.REVENUE, BalanceBucket.AVAILABLE, split.totalHp()));
        return entries;
    }

    // 반환 원장의 기대 줄 수(HP 종류별 이동 2줄)
    static int releaseLines(PointHoldDetail detail) {
        return (detail.getChargeAmountHp() > 0 ? 2 : 0) + (detail.getRevenueAmountHp() > 0 ? 2 : 0);
    }

    // 지급 원장의 기대 줄 수(보류 감소 HP 종류별 1줄 + 받는 쪽 증가 1줄)
    static int payoutLines(PointHoldDetail detail) {
        return (detail.getChargeAmountHp() > 0 ? 1 : 0) + (detail.getRevenueAmountHp() > 0 ? 1 : 0) + 1;
    }

    // 한 보류의 처리 상태: 잠근 보류, 처리 순서대로의 상세(이번 트랜잭션에서 더한 것 포함), 배분, 재생한 이력
    record HoldState(PointHold hold, List<PointHoldDetail> details, List<PointChargeUsage> usages,
            ChargeUsageHistory history) {

        // 멱등키로 이 보류의 상세를 찾는다.
        Optional<PointHoldDetail> detail(String idempotencyKey) {
            return details.stream().filter(detail -> detail.getIdempotencyKey().equals(idempotencyKey)).findFirst();
        }

        // 이 보류에 해당 처리(반환·지급) 이력이 있는지 확인한다.
        boolean has(HoldDetailActionType actionType) {
            return details.stream().anyMatch(detail -> detail.getActionType() == actionType);
        }
    }

    // 적용한 반환 한 건: 저장한 상세와 충전 건별 원금 복원
    record ReleaseApplied(PointHoldDetail detail, List<ChargeRestoration> restorations) {
    }

    // 적용한 지급 한 건: 저장한 상세와 충전 건별 소진
    record PayoutApplied(PointHoldDetail detail, List<ChargeConsumption> consumptions) {
    }
}
