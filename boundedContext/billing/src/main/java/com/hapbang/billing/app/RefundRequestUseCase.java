package com.hapbang.billing.app;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.PointRefund;
import com.hapbang.billing.domain.PointRefundRequest;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.PointRefundRepository;
import com.hapbang.billing.out.PointRefundRequestRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;

import lombok.RequiredArgsConstructor;

// 충전 HP 환불 요청을 접수한다 (#260). PG를 부르기 전에 한 트랜잭션에서 요청(REQUESTED)·충전 HP 보류(CHARGE_ONLY)·충전 건별
// 취소(PENDING)를 저장하고 커밋한다. 이후 어느 단계가 실패해도 저장된 요청·취소 건으로 추적·복구할 수 있다.
// - 금액: 1,000 HP 이상, 1,000 HP 단위(일반 환불). 회원 탈퇴 환불(1 HP 단위)은 회원 탈퇴가 구현되지 않아 열지 않는다
//   (회원이 고를 수 있는 값으로 두지 않는다).
// - 충전 건 배분: 보류와 같은 FIFO(충전 완료 시각, 충전 건 ID)로 가용 원금(사용·보류되지 않은 금액)에서만 배분한다. 건너뛰지 않으며,
//   배분한 원금은 보류가 끝날 때까지 다른 보류·환불에 쓰이지 않는다(같은 충전 건 원금 중복 환불 예약 없음).
// - 멱등성: 같은 회원의 같은 Idempotency-Key는 처음 요청이다(금액이 다르면 IDEMPOTENCY_KEY_CONFLICT).
@Service
@RequiredArgsConstructor
public class RefundRequestUseCase {

    static final long UNIT_HP = 1_000L;

    private final WalletRepository walletRepository;
    private final PointRefundRequestRepository pointRefundRequestRepository;
    private final PointRefundRepository pointRefundRepository;
    private final LedgerRecordUseCase ledgerRecordUseCase;
    private final InternalHoldFacade internalHoldFacade;

    // 환불 요청 ID를 돌려준다. 충전 HP 가용 잔액이 모자라면 INSUFFICIENT_BALANCE(409)로 거절하고 아무것도 남기지 않는다.
    @Transactional
    public Long create(Long userId, String clientKey, long amountHp) {
        if (amountHp < UNIT_HP || amountHp % UNIT_HP != 0) {
            throw new BillingException(BillingErrorCode.REFUND_AMOUNT_INVALID);
        }
        Long walletId = walletRepository.findByUserId(userId)
                .map(Wallet::getId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.WALLET_NOT_FOUND));
        // 같은 회원의 환불 요청 접수를 지갑 락으로 직렬화한다(같은 키 동시 요청은 뒤의 요청이 처음 요청을 본다).
        ledgerRecordUseCase.lockWallet(walletId);
        String key = BillingKeys.refundRequest(walletId, clientKey);
        Optional<PointRefundRequest> existing = pointRefundRequestRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            if (existing.get().getRequestedAmountHp() != amountHp) {
                throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return existing.get().getId();
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        PointRefundRequest request = pointRefundRequestRepository.save(
                PointRefundRequest.requested(walletId, amountHp, key, now));
        HoldResult hold = internalHoldFacade.hold(new InternalHoldCommand(userId,
                BillingReferenceType.POINT_REFUND_REQUEST, request.getId(), amountHp));
        request.attachHold(hold.holdId());
        long allocatedHp = 0;
        // 보류 배분 순서(FIFO)대로 저장하므로 개별 환불 ID 오름차순이 PG 취소 순서다
        for (HoldResult.ChargeAllocation allocation : hold.chargeAllocations()) {
            pointRefundRepository.save(PointRefund.pending(request, allocation.chargeId(), allocation.amountHp(),
                    BillingKeys.refundCancel(), now));
            allocatedHp += allocation.amountHp();
        }
        if (allocatedHp != amountHp || hold.chargeHp() != amountHp) {
            throw new BillingException(BillingErrorCode.REFUND_STATE_INCONSISTENT);
        }
        return request.getId();
    }
}
