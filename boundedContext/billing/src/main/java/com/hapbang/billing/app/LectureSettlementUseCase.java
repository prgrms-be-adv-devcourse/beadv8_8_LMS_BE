package com.hapbang.billing.app;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.PayoutType;
import com.hapbang.billing.domain.PointHold;
import com.hapbang.billing.domain.Settlement;
import com.hapbang.billing.domain.SettlementStatus;
import com.hapbang.billing.out.SettlementRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.LectureSettlementCommand;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;

// 강의 주문 항목별 판매 수익 지급 예정 건(SETTLEMENT, 원천 LECTURE_ORDER_ITEM)을 등록하고 지급 중단·재개·취소 상태를 관리한다(#142).
// 등록·상태 변경은 지급 건만 바꾸며 HP를 옮기지 않는다(지갑·보류·배분·원장 변경 없음). 실제 지급(SCHEDULED → COMPLETED)은 후속 Issue다.
// 모든 메서드는 호출한 쪽 트랜잭션에 참여하므로(REQUIRED), 강의가 구매 취소 반환(BillingPort.release)과 cancel을 한 트랜잭션에서 부를 수 있다.
@Service
@RequiredArgsConstructor
public class LectureSettlementUseCase {

    private final HoldProcessing holdProcessing;
    private final LedgerRecordUseCase ledgerRecordUseCase;
    private final SettlementRepository settlementRepository;
    private final EntityManager entityManager;

    // 금액 확인 → 구매자·판매자 지갑 확인 → 구매자 지갑 락 → 주문 항목 보류 락·소유 확인 → (같은 원천의 지급 건이 있으면 재요청·충돌 판단)
    // → 보류 상태·금액 대조 → 지급 예정 건 저장 순으로 처리한다. 같은 주문 항목의 등록은 지갑·보류 락으로 직렬화되고, 원천 UNIQUE가 최종으로 막는다.
    @Transactional
    public LectureSettlementResult schedule(LectureSettlementCommand command) {
        if (command.amountHp() <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        // 금융 거래처럼 지갑을 자동 생성하지 않는다. 판매자 지갑이 없으면 WALLET_NOT_FOUND다.
        Long buyerWalletId = holdProcessing.walletIdOf(command.buyerUserId());
        Long sellerWalletId = holdProcessing.walletIdOf(command.sellerUserId());
        // 같은 지갑 안에서 충전 HP가 수익 HP로 바뀌는 지급이 되므로(HP 간 전환 금지) 지급과 같은 규칙으로 거절한다.
        if (buyerWalletId.equals(sellerWalletId)) {
            throw new BillingException(BillingErrorCode.PAYOUT_TO_PAYER);
        }
        // 지갑 → 보류 락 순서(기존 보류·반환·지급과 같다). 보류 락이 같은 주문 항목의 등록·반환·이후 지급을 직렬화한다.
        ledgerRecordUseCase.lockWallet(buyerWalletId);
        PointHold hold = holdProcessing.lockHold(
                BillingKeys.hold(BillingReferenceType.LECTURE_ORDER_ITEM, command.orderItemId()), buyerWalletId);
        // 찾은 보류의 참조 유형·ID가 이 주문 항목인지 지급과 같은 규칙으로 확인한다(다른 주문 항목의 보류를 참조하지 않는다).
        HoldProcessing.verifySource(hold, SettlementSourceType.LECTURE_ORDER_ITEM, command.orderItemId());
        Instant scheduledAt = LectureSettlementSchedule.payableAt(command.purchasedAt());

        Optional<Settlement> existing = settlementRepository.findBySourceTypeAndSourceId(
                SettlementSourceType.LECTURE_ORDER_ITEM, command.orderItemId());
        if (existing.isPresent()) {
            return replay(existing.get(), hold, sellerWalletId, command, scheduledAt);
        }
        // 이미 반환·지급이 일어난 보류에는 새 예정 건을 만들지 않는다. 정산 금액은 그 주문 항목에 실제로 보류된 결제 HP 전부다.
        if (!hold.isOpen() || hold.heldTotalHp() == 0) {
            throw new BillingException(BillingErrorCode.HOLD_ALREADY_CLOSED);
        }
        if (hold.originalTotalHp() != command.amountHp() || hold.heldTotalHp() != command.amountHp()) {
            throw new BillingException(BillingErrorCode.SETTLEMENT_AMOUNT_MISMATCH);
        }
        Settlement settlement = holdProcessing.saveSettlement(Settlement.scheduledLecture(command.sellerUserId(),
                sellerWalletId, hold.getId(), command.orderItemId(), command.amountHp(),
                BillingKeys.lectureSettlement(command.orderItemId()), scheduledAt));
        return LectureSettlementResult.of(settlement, false);
    }

    // 이의 제기로 주문 항목의 지급을 멈춘다(SCHEDULED → SUSPENDED). 이미 중단된 건은 바꾸지 않는다.
    @Transactional
    public LectureSettlementResult suspend(Long orderItemId) {
        Settlement settlement = lockSettlement(orderItemId);
        return LectureSettlementResult.of(settlement, !settlement.suspend());
    }

    // 이의 제기 기각·지급 허용으로 지급을 다시 예정한다(SUSPENDED → SCHEDULED). 예정 시각은 처음 값을 유지한다.
    @Transactional
    public LectureSettlementResult resume(Long orderItemId) {
        Settlement settlement = lockSettlement(orderItemId);
        return LectureSettlementResult.of(settlement, !settlement.resume());
    }

    // 구매 취소·환불 결정으로 지급하지 않기로 한다(SCHEDULED·SUSPENDED → CANCELLED). 보류 반환은 하지 않는다:
    // 강의가 같은 트랜잭션에서 BillingPort.release로 구매자에게 반환한 **뒤에** 이 메서드를 부른다(락 순서 지갑 → 보류 → 지급 건).
    // 취소를 먼저 부르면 지급 건 락을 잡은 채 지갑·보류를 기다리게 되어, 지갑·보류를 먼저 잠그는 지급 처리와 교착할 수 있다. 행을 지우지 않는다.
    @Transactional
    public LectureSettlementResult cancel(Long orderItemId) {
        Settlement settlement = lockSettlement(orderItemId);
        return LectureSettlementResult.of(settlement, !settlement.cancel());
    }

    // 주문 항목의 지급 건이 주어진 시각에 지급 상태 조건을 만족하는지 확인한다(SCHEDULED이고 예정 시각 경과). 락 없이 읽은 값이다.
    // 지급 승인이 아니다: 실제 지급은 지갑·보류·지급 건을 잠근 뒤 이 조건을 다시 확인하고, 강의 구매·이의 제기 상태와 판매자 자격(탈퇴 아님)도 확인해야 한다.
    @Transactional(readOnly = true)
    public boolean isPayable(Long orderItemId, Instant now) {
        return findSettlement(orderItemId).isPayableAt(now);
    }

    // 같은 원천의 지급 건이 이미 있으면 같은 등록 요청인지 확인한다. 취소된 건은 다시 등록하지 않으며(SETTLEMENT_INVALID_STATE),
    // 보류·받는 회원·받는 지갑·금액·예정 시각(구매 시각)·지급 유형이 다르면 IDEMPOTENCY_KEY_CONFLICT다. 같으면 현재 상태 그대로 돌려준다.
    private LectureSettlementResult replay(Settlement settlement, PointHold hold, Long sellerWalletId,
            LectureSettlementCommand command, Instant scheduledAt) {
        if (settlement.getStatus() == SettlementStatus.CANCELLED) {
            throw new BillingException(BillingErrorCode.SETTLEMENT_INVALID_STATE);
        }
        if (!settlement.getPointHoldId().equals(hold.getId())
                || settlement.getPayoutType() != PayoutType.NORMAL
                || !settlement.getReceiverUserId().equals(command.sellerUserId())
                || !settlement.getReceiverWalletId().equals(sellerWalletId)
                || settlement.getGrossAmountHp() != command.amountHp()
                || !settlement.getScheduledAt().equals(scheduledAt)) {
            throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        return LectureSettlementResult.of(settlement, true);
    }

    // 주문 항목의 지급 건을 찾아 쓰기 락을 걸고 DB의 최신 상태로 다시 읽는다. 락 순서상 마지막(지갑 → 보류 → 지급 건)이라
    // 이후 지급 처리(지갑·보류를 먼저 잠근다)와 교착하지 않는다. 같은 주문 항목의 중단·재개·취소는 이 락으로 직렬화된다.
    private Settlement lockSettlement(Long orderItemId) {
        Settlement settlement = findSettlement(orderItemId);
        entityManager.refresh(settlement, LockModeType.PESSIMISTIC_WRITE);
        return settlement;
    }

    private Settlement findSettlement(Long orderItemId) {
        return settlementRepository.findBySourceTypeAndSourceId(SettlementSourceType.LECTURE_ORDER_ITEM, orderItemId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.SETTLEMENT_NOT_FOUND));
    }
}
