package com.hapbang.billing.app;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.app.HoldProcessing.HoldState;
import com.hapbang.billing.app.HoldProcessing.ReleaseApplied;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.HoldDetailActionType;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointHold;
import com.hapbang.billing.domain.PointHoldDetail;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.shared.billing.dto.ReleaseCommand;

import lombok.RequiredArgsConstructor;

// 참조 대상 보류의 남은 HP 일부 또는 전부를 결제한 회원에게 반환한다. 충전 HP를 먼저, 부족분은 수익 HP를 반환하고
// 충전 HP는 최초 배분의 역순으로 원래 충전 건의 가용 원금에 되돌린다. 보류·반환 상세·배분·충전 원금·원장·지갑 잔액을 한 트랜잭션에서 바꾼다.
@Service
@RequiredArgsConstructor
public class HoldReleaseUseCase {

    private static final String DESCRIPTION = "HP 반환";

    private final HoldProcessing holdProcessing;
    private final LedgerRecordUseCase ledgerRecordUseCase;

    // 지갑 락 → 보류 락·소유 확인 → 처리 이력 재생·대조 → (재요청이면 처음 결과) → 반환 배분 계산
    // → 원금 대조 → 충전 건 락·복원 → 보류·배분 변경 → 반환 상세 저장 → 원장 기록 순으로 처리한다.
    // 호출한 쪽 트랜잭션이 있으면 그 안에서 실행되며, 어느 단계든 실패하면 모든 변경이 롤백된다.
    @Transactional
    public ReleaseResult release(ReleaseCommand command) {
        BillingCommandValidator.verify(command);
        Long walletId = holdProcessing.walletIdOf(command.userId());
        // 보류·배분·충전 원금은 소유 지갑의 락 아래에서만 바뀌므로, 이 락으로 같은 지갑의 보류·반환·지급이 직렬화된다.
        // lockWallet이 먼저 flush하므로 이후 refresh가 같은 트랜잭션의 미반영 변경을 지우지 않는다.
        Wallet wallet = ledgerRecordUseCase.lockWallet(walletId);
        PointHold hold = holdProcessing.lockHold(BillingKeys.hold(command), walletId);

        String key = BillingKeys.release(hold.getId(), command);
        HoldState state = holdProcessing.load(hold);

        Optional<PointHoldDetail> existing = state.detail(key);
        if (existing.isPresent()) {
            return replay(command, state, existing.get());
        }
        // 반환 상세 없이 원장만 있는 상태는 정상 재요청이 아니므로 새로 반환하지 않는다.
        if (holdProcessing.ledgerLines(key) > 0) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        // 이미 끝난 보류는 다른 새 요청으로 다시 반환하지 않는다. 전체 반환은 처음 처리할 때 남아 있는 보류액을 기준으로 한다.
        if (!hold.isOpen() || hold.heldTotalHp() == 0) {
            throw new BillingException(BillingErrorCode.HOLD_ALREADY_CLOSED);
        }
        long amountHp = command.isFull() ? hold.heldTotalHp() : command.amountHp();
        ReleaseSplit split = ReleaseSplit.of(hold.getHeldChargeHp(), hold.getHeldRevenueHp(), amountHp);
        holdProcessing.verifyChargePrincipal(wallet);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ReleaseApplied applied = holdProcessing.release(state, wallet, split, key, now);
        // record()는 시작할 때 flush하므로 위의 보류·배분·충전 원금·상세 변경도 원장보다 먼저 같은 트랜잭션에서 DB에 반영된다.
        // 지갑은 여기까지 바꾸지 않았으므로 record()의 refresh가 지울 변경이 없고, 잔액은 record()가 원장으로만 바꾼다.
        ledgerRecordUseCase.record(new LedgerOperation(key, LedgerTransactionType.RELEASE, hold.getReferenceType(),
                hold.getReferenceId(), DESCRIPTION, now, HoldProcessing.releaseEntries(walletId, split)));
        return ReleaseResult.of(hold, applied.detail(), applied.restorations(), false);
    }

    // 이미 처리한 같은 요청이면 현재 보류 상태와 관계없이 다시 바꾸지 않고 그때의 반환 결과를 돌려준다.
    // 부분 반환 금액이 처음과 다르면 IDEMPOTENCY_KEY_CONFLICT, 원장 줄 수가 맞지 않으면 HOLD_STATE_INCONSISTENT로 거절한다.
    // 전체 반환 재요청은 금액을 비교하지 않고 처음 반환한 금액을 그대로 돌려준다(남은 보류를 다시 계산하지 않는다).
    private ReleaseResult replay(ReleaseCommand command, HoldState state, PointHoldDetail detail) {
        if (detail.getActionType() != HoldDetailActionType.RELEASE
                || (!command.isFull() && detail.totalAmountHp() != command.amountHp())) {
            throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        if (holdProcessing.ledgerLines(detail.getIdempotencyKey()) != HoldProcessing.releaseLines(detail)) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        return ReleaseResult.of(state.hold(), detail, state.history().restorationsOf(detail.getId()), true);
    }
}
