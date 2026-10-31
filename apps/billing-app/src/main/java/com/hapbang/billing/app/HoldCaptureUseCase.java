package com.hapbang.billing.app;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.app.HoldProcessing.CaptureApplied;
import com.hapbang.billing.app.HoldProcessing.HoldState;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.HoldDetailActionType;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointHold;
import com.hapbang.billing.domain.PointHoldDetail;
import com.hapbang.billing.domain.PointHoldType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.shared.billing.domain.BillingReferenceType;

import lombok.RequiredArgsConstructor;

// 보류 HP를 다른 회원에게 옮기지 않고 최종 차감한다(확정 차감, #241). 환불(CHARGE_ONLY)은 PG 취소 성공액만, 환전(REVENUE_ONLY)은
// 송금 성공 확인 후 차감한다. 전체·부분 확정 차감을 허용하고 남은 금액은 보류로 남아 추가 확정 차감·반환할 수 있다.
// 충전 HP는 배분 순서(FIFO)로, 충전 건을 지정하면 그 배분에서만 소진하며 소진한 원금은 충전 건에 돌아가지 않는다.
// 강의·출연 보류(MIXED)는 확정 차감하지 않는다(지급은 내부 API 지급). 보류·상세·배분·원장·지갑을 한 트랜잭션에서 바꾼다.
@Service
@RequiredArgsConstructor
public class HoldCaptureUseCase {

    private final HoldProcessing holdProcessing;
    private final LedgerRecordUseCase ledgerRecordUseCase;

    // 지갑 락 → 보류 락·소유 확인 → 처리 이력 재생·대조 → (재요청이면 처음 결과) → 확정 차감 배분 계산 → 원금 대조
    // → 배분 소진·보류 감소 → 확정 차감 상세 저장 → 원장 기록 순으로 처리한다(반환·지급과 같은 지갑 → 보류 → 충전 건 락 순서).
    // 호출한 쪽 트랜잭션이 있으면 그 안에서 실행되며, 어느 단계든 실패하면 모든 변경이 롤백된다.
    @Transactional
    public CaptureResult capture(CaptureCommand command) {
        if (!command.isFull()) {
            BillingCommandValidator.requirePositive(command.amountHp());
        }
        Long walletId = holdProcessing.walletIdOf(command.userId());
        Wallet wallet = ledgerRecordUseCase.lockWallet(walletId);
        PointHold hold = holdProcessing.lockHold(BillingKeys.hold(command.referenceType(), command.referenceId()),
                walletId);
        if (hold.getHoldType() == PointHoldType.MIXED) {
            throw new BillingException(BillingErrorCode.HOLD_TYPE_NOT_SUPPORTED);
        }

        String key = BillingKeys.capture(hold.getId(), command.captureRequestId());
        HoldState state = holdProcessing.load(hold);

        Optional<PointHoldDetail> existing = state.detail(key);
        if (existing.isPresent()) {
            return replay(command, state, existing.get());
        }
        // 확정 차감 상세 없이 원장만 있는 상태는 정상 재요청이 아니므로 새로 차감하지 않는다.
        if (holdProcessing.ledgerLines(key) > 0) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        if (!hold.isOpen() || hold.heldTotalHp() == 0) {
            throw new BillingException(BillingErrorCode.HOLD_ALREADY_CLOSED);
        }
        CaptureSplit split = command.chargeId() == null
                ? CaptureSplit.of(hold.getHoldType(), hold.getHeldChargeHp(), hold.getHeldRevenueHp(),
                        command.isFull() ? hold.heldTotalHp() : command.amountHp())
                : new CaptureSplit(holdProcessing.targetedChargeAmount(state, command.chargeId(), command.amountHp(),
                        BillingErrorCode.CAPTURE_AMOUNT_EXCEEDS_HELD), 0L);
        holdProcessing.verifyChargePrincipal(wallet);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        CaptureApplied applied = holdProcessing.capture(state, split, key, command.chargeId());
        // 보류 HP만 줄인다(받는 지갑 없음). 지갑 잔액은 record()가 원장으로만 바꾼다.
        ledgerRecordUseCase.record(new LedgerOperation(key, transactionTypeOf(hold.getReferenceType()),
                hold.getReferenceType(), hold.getReferenceId(), descriptionOf(hold.getReferenceType()), now,
                HoldProcessing.captureEntries(walletId, split)));
        return CaptureResult.of(hold, applied.detail(), applied.consumptions(), false);
    }

    // 이미 처리한 같은 요청이면 현재 보류 상태와 관계없이 다시 바꾸지 않고 그때의 결과를 돌려준다.
    // 부분 금액·지정한 충전 건이 처음과 다르면 IDEMPOTENCY_KEY_CONFLICT, 원장 줄 수가 맞지 않으면 HOLD_STATE_INCONSISTENT다.
    private CaptureResult replay(CaptureCommand command, HoldState state, PointHoldDetail detail) {
        if (detail.getActionType() != HoldDetailActionType.CAPTURE
                || (!command.isFull() && detail.totalAmountHp() != command.amountHp())
                || !Objects.equals(detail.getTargetChargeId(), command.chargeId())) {
            throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        if (holdProcessing.ledgerLines(detail.getIdempotencyKey()) != HoldProcessing.captureLines(detail)) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        return CaptureResult.of(state.hold(), detail, state.history().consumptionsOf(detail.getId()), true);
    }

    // 확정 차감 원장의 거래 유형: 환불 보류는 REFUND, 환전 보류는 EXCHANGE
    static LedgerTransactionType transactionTypeOf(BillingReferenceType referenceType) {
        return switch (referenceType) {
            case POINT_REFUND_REQUEST -> LedgerTransactionType.REFUND;
            case EXCHANGE -> LedgerTransactionType.EXCHANGE;
            default -> throw new BillingException(BillingErrorCode.HOLD_TYPE_NOT_SUPPORTED);
        };
    }

    private static String descriptionOf(BillingReferenceType referenceType) {
        return referenceType == BillingReferenceType.EXCHANGE ? "환전 송금 확정 차감" : "충전 HP 환불 확정 차감";
    }
}
