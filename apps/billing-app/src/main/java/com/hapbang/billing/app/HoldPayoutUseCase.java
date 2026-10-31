package com.hapbang.billing.app;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.app.HoldProcessing.HoldState;
import com.hapbang.billing.app.HoldProcessing.PayoutApplied;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.HoldDetailActionType;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PayoutType;
import com.hapbang.billing.domain.PointHold;
import com.hapbang.billing.domain.PointHoldDetail;
import com.hapbang.billing.domain.Settlement;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.shared.billing.dto.PayoutCommand;

import lombok.RequiredArgsConstructor;

// 참조 대상 보류의 남은 HP 일부 또는 전부를 받는 회원의 수익 HP로 지급한다(정상 지급). 수익 HP 보류를 먼저, 부족분은 충전 HP 보류를
// 최초 배분 순서(FIFO)로 소진하며, 소진한 충전 원금은 보류한 회원에게 돌아가지 않는다. 받는 쪽에는 출처와 관계없이 수익 HP로 들어간다.
// 보류·지급 상세·배분·지급 건·원장·두 지갑 잔액을 한 트랜잭션에서 바꾼다.
@Service
@RequiredArgsConstructor
public class HoldPayoutUseCase {

    private static final String DESCRIPTION = "HP 지급";

    private final HoldProcessing holdProcessing;
    private final LedgerRecordUseCase ledgerRecordUseCase;

    // 두 지갑 ID 오름차순 락 → 보류 락·소유·지급 원천 확인 → 처리 이력 재생·대조 → (재요청이면 처음 결과) → 지급 배분 계산
    // → 원금 대조 → 배분 소진·보류 감소 → 지급 상세·지급 건 저장 → 원장 기록 순으로 처리한다.
    // 호출한 쪽 트랜잭션이 있으면 그 안에서 실행되며, 어느 단계든 실패하면 모든 변경이 롤백된다.
    @Transactional
    public PayoutResult payout(PayoutCommand command) {
        BillingCommandValidator.verify(command);
        Long payerWalletId = holdProcessing.walletIdOf(command.payerUserId());
        Long receiverWalletId = holdProcessing.walletIdOf(command.receiverUserId());
        // 같은 지갑 안의 이동은 충전 HP를 수익 HP로 바꾸는 것이 되므로(HP 간 전환 금지) 거절한다.
        if (payerWalletId.equals(receiverWalletId)) {
            throw new BillingException(BillingErrorCode.PAYOUT_TO_PAYER);
        }
        // 보류·충전 건보다 먼저 두 지갑을 ID 오름차순으로 잠근다. 반대 방향 지급이 동시에 와도 같은 순서로 잠가 교착이 없다.
        Map<Long, Wallet> wallets = ledgerRecordUseCase.lockWallets(List.of(payerWalletId, receiverWalletId));
        PointHold hold = holdProcessing.lockHold(BillingKeys.hold(command), payerWalletId);
        HoldProcessing.verifySource(hold, command.sourceType(), command.sourceId());

        String key = BillingKeys.payout(hold.getId(), command);
        HoldState state = holdProcessing.load(hold);

        Optional<PointHoldDetail> existing = state.detail(key);
        if (existing.isPresent()) {
            return replay(command, state, existing.get(), receiverWalletId);
        }
        // 지급 상세 없이 원장이나 지급 건만 있는 상태는 정상 재요청이 아니므로 새로 지급하지 않는다.
        if (holdProcessing.ledgerLines(key) > 0 || holdProcessing.settlement(key).isPresent()) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        if (!hold.isOpen() || hold.heldTotalHp() == 0) {
            throw new BillingException(BillingErrorCode.HOLD_ALREADY_CLOSED);
        }
        holdProcessing.rejectSettledSource(hold, command.sourceType(), command.sourceId());
        long amountHp = command.isFull() ? hold.heldTotalHp() : command.amountHp();
        PayoutSplit split = PayoutSplit.of(hold.getHeldRevenueHp(), hold.getHeldChargeHp(), amountHp);
        holdProcessing.verifyChargePrincipal(wallets.get(payerWalletId));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        PayoutApplied applied = holdProcessing.payout(state, split, key);
        Settlement settlement = holdProcessing.saveSettlement(Settlement.completed(command.receiverUserId(),
                receiverWalletId, hold.getId(), command.sourceType(), command.sourceId(), PayoutType.NORMAL,
                split.totalHp(), key, now));
        // 두 지갑의 잔액은 record()가 원장으로만 바꾼다(이미 잡은 락을 그대로 쓴다).
        ledgerRecordUseCase.record(new LedgerOperation(key, LedgerTransactionType.PAYOUT, hold.getReferenceType(),
                hold.getReferenceId(), DESCRIPTION, now,
                HoldProcessing.payoutEntries(payerWalletId, receiverWalletId, split)));
        return PayoutResult.of(hold, applied.detail(), settlement, applied.consumptions(), false);
    }

    // 이미 처리한 같은 요청이면 현재 보류 상태와 관계없이 다시 바꾸지 않고 그때의 지급 결과를 돌려준다.
    // 받는 지갑·지급 원천·(부분 지급이면) 금액이 처음과 다르면 IDEMPOTENCY_KEY_CONFLICT, 지급 건이나 원장 줄이 맞지 않으면
    // HOLD_STATE_INCONSISTENT로 거절한다. 전체 지급 재요청은 금액을 비교하지 않고 처음 지급한 금액을 그대로 돌려준다.
    private PayoutResult replay(PayoutCommand command, HoldState state, PointHoldDetail detail,
            Long receiverWalletId) {
        Settlement settlement = holdProcessing.settlement(detail.getIdempotencyKey())
                .orElseThrow(() -> new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT));
        if (detail.getActionType() != HoldDetailActionType.PAYOUT
                || settlement.getPayoutType() != PayoutType.NORMAL
                || !settlement.getReceiverWalletId().equals(receiverWalletId)
                || settlement.getSourceType() != command.sourceType()
                || !settlement.getSourceId().equals(command.sourceId())
                || (!command.isFull() && detail.totalAmountHp() != command.amountHp())) {
            throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        if (settlement.getPaidAmountHp() != detail.totalAmountHp()
                || holdProcessing.ledgerLines(detail.getIdempotencyKey()) != HoldProcessing.payoutLines(detail)) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        return PayoutResult.of(state.hold(), detail, settlement, state.history().consumptionsOf(detail.getId()), true);
    }
}
