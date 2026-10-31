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
import com.hapbang.billing.app.HoldProcessing.ReleaseApplied;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PayoutType;
import com.hapbang.billing.domain.PointHold;
import com.hapbang.billing.domain.PointHoldDetail;
import com.hapbang.billing.domain.Settlement;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.shared.billing.dto.PenaltyCommand;

import lombok.RequiredArgsConstructor;

// 호스트 일방 취소 위약금을 한 트랜잭션에서 정산한다: 출연이 계산한 호스트 반환액을 반환 정책(충전 HP 우선·충전 원금 역순 복원)으로
// 호스트에게 반환하고, 출연이 계산한 게스트 지급액을 지급 정책(수익 HP 우선·충전 원금 FIFO 소진)으로 게스트 수익 HP에 지급한다.
// 위약금 비율·금액·반올림은 출연이 정하며 Billing은 계산하거나 검증하지 않는다(#145). 두 금액의 합이 이 취소 건의 정산 기준액이다.
// 반환을 먼저, 지급을 다음에 적용한다(상세 ID 순서 = 처리 순서). 반환과 지급은 서로 다른 끝(충전 HP·나중 배분 / 수익 HP·먼저 배분)부터
// 쓰므로 정산 기준액이 남은 보류 이하면 순서와 관계없이 HP 종류·충전 건별 결과가 같다. 반환액이 0이면 반환 상세·원장을 만들지 않는다.
// 독립 트랜잭션으로 반환을 먼저 커밋하지 않으며, 어느 단계든 실패하면 반환·지급·지급 건·원장이 모두 롤백된다.
@Service
@RequiredArgsConstructor
public class PenaltySettlementUseCase {

    private static final String RELEASE_DESCRIPTION = "출연 취소 위약금 정산: 호스트 반환";
    private static final String PAYOUT_DESCRIPTION = "출연 취소 위약금 정산: 게스트 위약금 지급";

    private final HoldProcessing holdProcessing;
    private final LedgerRecordUseCase ledgerRecordUseCase;

    // 금액 확인 → 두 지갑 ID 오름차순 락 → 보류 락·소유·지급 원천 확인 → 처리 이력 재생·대조 → (재요청이면 처음 결과)
    // → 정산 기준액 ≤ 남은 보류 확인 → 원금 대조 → 반환 적용(반환액 > 0일 때) → 위약금 지급 적용 → 위약금 지급 건 저장 → 반환·지급 원장 기록 순으로 처리한다.
    @Transactional
    public PenaltyResult settle(PenaltyCommand command) {
        long settlementAmountHp = BillingCommandValidator.verify(command);
        Long payerWalletId = holdProcessing.walletIdOf(command.payerUserId());
        Long receiverWalletId = holdProcessing.walletIdOf(command.receiverUserId());
        if (payerWalletId.equals(receiverWalletId)) {
            throw new BillingException(BillingErrorCode.PAYOUT_TO_PAYER);
        }
        Map<Long, Wallet> wallets = ledgerRecordUseCase.lockWallets(List.of(payerWalletId, receiverWalletId));
        PointHold hold = holdProcessing.lockHold(BillingKeys.hold(command), payerWalletId);
        HoldProcessing.verifySource(hold, command.sourceType(), command.sourceId());

        String settlementKey = BillingKeys.penalty(hold.getId(), command);
        String releaseKey = BillingKeys.penaltyRelease(hold.getId(), command);
        String payoutKey = BillingKeys.penaltyPayout(hold.getId(), command);
        HoldState state = holdProcessing.load(hold);

        Optional<PointHoldDetail> releaseDetail = state.detail(releaseKey);
        Optional<PointHoldDetail> payoutDetail = state.detail(payoutKey);
        Optional<Settlement> settlement = holdProcessing.settlement(settlementKey);
        if (releaseDetail.isPresent() || payoutDetail.isPresent() || settlement.isPresent()) {
            return replay(command, settlementAmountHp, state, releaseDetail, payoutDetail, settlement,
                    receiverWalletId, releaseKey);
        }
        // 상세 없이 원장만 있는 상태는 정상 재요청이 아니므로 새로 정산하지 않는다.
        if (holdProcessing.ledgerLines(releaseKey) > 0 || holdProcessing.ledgerLines(payoutKey) > 0) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        if (!hold.isOpen() || hold.heldTotalHp() == 0) {
            throw new BillingException(BillingErrorCode.HOLD_ALREADY_CLOSED);
        }
        // 정산 기준액(반환액 + 지급액)은 출연이 정한 그 출연 몫이며, 이미 반환·지급된 금액까지 다시 정산하지 않도록 남은 보류 이하만 받는다.
        if (settlementAmountHp > hold.heldTotalHp()) {
            throw new BillingException(BillingErrorCode.PENALTY_BASE_EXCEEDS_HELD);
        }
        // 다른 취소 ID로 같은 출연에 위약금을 두 번 지급하거나, 정상 지급한 출연에 위약금을 지급하지 않는다.
        holdProcessing.rejectSettledSource(hold, command.sourceType(), command.sourceId());
        holdProcessing.verifyChargePrincipal(wallets.get(payerWalletId));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ReleaseSplit releaseSplit = null;
        ReleaseApplied released = null;
        if (command.releaseAmountHp() > 0) {
            releaseSplit = ReleaseSplit.of(hold.getHeldChargeHp(), hold.getHeldRevenueHp(), command.releaseAmountHp());
            released = holdProcessing.release(state, wallets.get(payerWalletId), releaseSplit, releaseKey, now);
        }
        PayoutSplit payoutSplit = PayoutSplit.of(hold.getHeldRevenueHp(), hold.getHeldChargeHp(),
                command.payoutAmountHp());
        PayoutApplied paid = holdProcessing.payout(state, payoutSplit, payoutKey);
        Settlement penalty = holdProcessing.saveSettlement(Settlement.completed(command.receiverUserId(),
                receiverWalletId, hold.getId(), command.sourceType(), command.sourceId(), PayoutType.PENALTY,
                command.payoutAmountHp(), settlementKey, now));
        // 반환·지급 원장을 각자의 작업 키로 같은 트랜잭션에 기록한다. 두 작업 키는 정산 키(취소 ID)를 접두사로 공유한다.
        if (releaseSplit != null) {
            ledgerRecordUseCase.record(new LedgerOperation(releaseKey, LedgerTransactionType.RELEASE,
                    hold.getReferenceType(), hold.getReferenceId(), RELEASE_DESCRIPTION, now,
                    HoldProcessing.releaseEntries(payerWalletId, releaseSplit)));
        }
        ledgerRecordUseCase.record(new LedgerOperation(payoutKey, LedgerTransactionType.PAYOUT,
                hold.getReferenceType(), hold.getReferenceId(), PAYOUT_DESCRIPTION, now,
                HoldProcessing.payoutEntries(payerWalletId, receiverWalletId, payoutSplit)));
        // 결과의 남은 보류액·보류 상태는 반환·지급을 모두 적용한 응답 시점 값이다(재요청 결과와 같다).
        ReleaseResult releaseResult = released == null ? null
                : ReleaseResult.of(hold, released.detail(), released.restorations(), false);
        PayoutResult payoutResult = PayoutResult.of(hold, paid.detail(), penalty, paid.consumptions(), false);
        return new PenaltyResult(hold.getId(), command.cancellationId(), settlementAmountHp,
                command.releaseAmountHp(), command.payoutAmountHp(), releaseResult, payoutResult, false);
    }

    // 이미 정산한 같은 취소 사건이면 현재 보류 상태와 관계없이 다시 바꾸지 않고 그때의 정산 결과를 돌려준다.
    // 호스트 반환액·게스트 지급액·받는 지갑·지급 원천이 처음과 다르면 IDEMPOTENCY_KEY_CONFLICT로 거절한다.
    // 지급 상세·지급 건 중 하나만 있거나, 반환 상세 없이 반환 원장만 있거나, 원장 줄이 맞지 않으면
    // (한 트랜잭션이라 생길 수 없는) 비정상 상태이므로 HOLD_STATE_INCONSISTENT로 거절한다. 반환액 0으로 처리한 건에는 반환 상세가 없다.
    private PenaltyResult replay(PenaltyCommand command, long settlementAmountHp, HoldState state,
            Optional<PointHoldDetail> releaseDetail, Optional<PointHoldDetail> payoutDetail,
            Optional<Settlement> settlement, Long receiverWalletId, String releaseKey) {
        if (payoutDetail.isEmpty() || settlement.isEmpty()
                || (releaseDetail.isEmpty() && holdProcessing.ledgerLines(releaseKey) > 0)) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        PointHoldDetail paid = payoutDetail.get();
        Settlement penalty = settlement.get();
        long releasedHp = releaseDetail.map(PointHoldDetail::totalAmountHp).orElse(0L);
        if (releasedHp != command.releaseAmountHp()
                || paid.totalAmountHp() != command.payoutAmountHp()
                || penalty.getPayoutType() != PayoutType.PENALTY
                || !penalty.getReceiverWalletId().equals(receiverWalletId)
                || penalty.getSourceType() != command.sourceType()
                || !penalty.getSourceId().equals(command.sourceId())) {
            throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        if (penalty.getPaidAmountHp() != paid.totalAmountHp()
                || (releaseDetail.isPresent() && holdProcessing.ledgerLines(releaseKey)
                        != HoldProcessing.releaseLines(releaseDetail.get()))
                || holdProcessing.ledgerLines(paid.getIdempotencyKey()) != HoldProcessing.payoutLines(paid)) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        ReleaseResult releaseResult = releaseDetail
                .map(released -> ReleaseResult.of(state.hold(), released,
                        state.history().restorationsOf(released.getId()), true))
                .orElse(null);
        PayoutResult payoutResult = PayoutResult.of(state.hold(), paid, penalty,
                state.history().consumptionsOf(paid.getId()), true);
        return new PenaltyResult(state.hold().getId(), command.cancellationId(), settlementAmountHp,
                command.releaseAmountHp(), command.payoutAmountHp(), releaseResult, payoutResult, true);
    }
}
