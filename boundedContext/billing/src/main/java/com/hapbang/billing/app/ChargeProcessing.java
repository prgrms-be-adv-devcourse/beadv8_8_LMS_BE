package com.hapbang.billing.app;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointCharge;
import com.hapbang.billing.domain.PointChargeStatus;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.PaymentKeyCipher;
import com.hapbang.billing.out.PointChargeRepository;
import com.hapbang.billing.out.TossPayment;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 충전 승인 흐름의 DB 단계를 각각 짧은 트랜잭션으로 처리한다 (#224). TossPayments 호출은 이 단계들 사이(트랜잭션 밖)에서 한다.
// - claim: 충전 건만 잠그고(지갑 락 없음) 주문·소유·금액을 확인한 뒤 PENDING → PROCESSING으로 바꾸며 결제키 암호문·HMAC을 남긴다.
// - complete: 지갑 → 충전 건 순서로 잠그고(보류·반환과 같은 순서) 승인 결과를 대조한 뒤 COMPLETED + 충전 원장을 한 트랜잭션에 기록한다.
// - fail·noteUnresolved: 승인 실패 확정·결과 미확정 사유를 남긴다.
// 모든 단계는 현재 상태를 다시 읽고 판단하므로 같은 충전 건을 여러 요청·배치가 동시에 처리해도 HP는 한 번만 늘어난다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ChargeProcessing {

    static final String PAYMENT_KEY_HASH_UNIQUE = "uk_point_charge_pg_payment_key_hash";
    private static final String LEDGER_DESCRIPTION = "HP 충전";

    private final WalletRepository walletRepository;
    private final PointChargeRepository pointChargeRepository;
    private final LedgerRecordUseCase ledgerRecordUseCase;
    private final PaymentKeyCipher paymentKeyCipher;
    private final EntityManager entityManager;

    // 승인 요청을 시작할 수 있는지 판단한다. 처음이면 PROCESSING으로 바꾸고(커밋 후 PG 승인), 같은 결제의 재요청이면 기존 결과나
    // 결과 확인으로 넘긴다. 다른 회원의 주문은 없는 주문과 같게(CHARGE_NOT_FOUND) 본다.
    @Transactional
    public Claim claim(Long userId, String orderId, long amount, String paymentKey) {
        Long walletId = walletRepository.findByUserId(userId)
                .map(Wallet::getId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.WALLET_NOT_FOUND));
        PointCharge charge = pointChargeRepository.findByPgOrderIdForUpdate(orderId)
                .filter(found -> found.getWalletId().equals(walletId))
                .orElseThrow(() -> new BillingException(BillingErrorCode.CHARGE_NOT_FOUND));
        // 클라이언트가 보낸 금액이 아니라 서버에 저장한 주문 금액이 기준이다. 다르면 PG를 부르지 않는다
        if (charge.getAmountHp() != amount) {
            throw new BillingException(BillingErrorCode.CHARGE_AMOUNT_MISMATCH);
        }
        String paymentKeyHash = paymentKeyCipher.hash(paymentKey);
        if (charge.isSettled()) {
            return replayOrConflict(charge, paymentKeyHash, new Claim.Replay(ChargeResult.from(charge)));
        }
        if (charge.getStatus() == PointChargeStatus.FAILED) {
            throw new BillingException(BillingErrorCode.CHARGE_ALREADY_FAILED);
        }
        if (charge.getStatus() == PointChargeStatus.PROCESSING) {
            return replayOrConflict(charge, paymentKeyHash, new Claim.Recover(charge.getId()));
        }
        // PENDING: 같은 결제키가 다른 주문에 쓰였으면 거절한다(최종 방어는 HMAC UNIQUE)
        if (pointChargeRepository.existsByPgPaymentKeyHash(paymentKeyHash)) {
            throw new BillingException(BillingErrorCode.CHARGE_PAYMENT_KEY_CONFLICT);
        }
        charge.startConfirm(paymentKeyCipher.encrypt(paymentKey), paymentKeyHash);
        try {
            // Repository를 거쳐 flush해야 UNIQUE 위반이 DataIntegrityViolationException으로 바뀐다(EntityManager 직접 flush는 바뀌지 않는다)
            pointChargeRepository.saveAndFlush(charge);
        } catch (DataIntegrityViolationException exception) {
            // 다른 주문이 같은 결제키로 동시에 승인 요청을 시작해 먼저 커밋한 경우
            if (PAYMENT_KEY_HASH_UNIQUE.equals(HoldProcessing.uniqueViolation(exception))) {
                throw new BillingException(BillingErrorCode.CHARGE_PAYMENT_KEY_CONFLICT, exception);
            }
            throw exception;
        }
        return new Claim.Start(charge.getId());
    }

    // PG 승인이 확인된 결제로 충전을 확정한다. 이미 확정되었으면 다시 바꾸지 않고(HP 한 번), 승인 결과가 주문과 다르면
    // 충전하지 않고 사유만 남긴 채 Mismatch를 돌려준다(커밋된다). 지갑이 없으면 WALLET_NOT_FOUND로 전부 롤백된다.
    @Transactional
    public Completion complete(Long chargeId, TossPayment payment) {
        Long walletId = pointChargeRepository.findById(chargeId)
                .orElseThrow(() -> new IllegalStateException("충전 건이 없습니다. chargeId=" + chargeId))
                .getWalletId();
        // 지갑 → 충전 건 락 순서(보류 생성의 지갑 → 충전 건과 같다)
        ledgerRecordUseCase.lockWallet(walletId);
        PointCharge charge = lockCharge(chargeId);
        if (charge.isSettled()) {
            return new Completion.Completed(ChargeResult.from(charge));
        }
        if (charge.getStatus() != PointChargeStatus.PROCESSING) {
            String reason = "APPROVED_BUT_" + charge.getStatus();
            log.error("PG 승인이 확인됐지만 충전 건이 처리 중이 아니다. 운영자 확인 필요: chargeId={}, orderId={}, payment={}",
                    chargeId, charge.getPgOrderId(), payment);
            return new Completion.Mismatch(reason);
        }
        String mismatch = mismatchOf(charge, payment);
        if (mismatch != null) {
            noteUnresolved(charge, mismatch);
            log.error("PG 승인 결과가 충전 주문과 다르다. 충전하지 않는다. 운영자 확인 필요: chargeId={}, orderId={}, reason={}, payment={}",
                    chargeId, charge.getPgOrderId(), mismatch, payment);
            return new Completion.Mismatch(mismatch);
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        charge.complete(now);
        // 충전 HP 가용 잔액 증가는 원장으로만 한다. 작업 키 CHARGE:{chargeId}로 충전 건당 한 번만 기록된다
        ledgerRecordUseCase.record(new LedgerOperation(BillingKeys.charge(chargeId), LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, chargeId, LEDGER_DESCRIPTION, now,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, charge.getAmountHp()))));
        return new Completion.Completed(ChargeResult.from(charge));
    }

    // PG가 승인하지 않은 것이 확정되어 실패로 끝낸다. 이미 다른 요청이 확정·실패 처리했으면 그대로 둔다.
    @Transactional
    public ChargeResult fail(Long chargeId, String reason) {
        PointCharge charge = lockCharge(chargeId);
        if (charge.getStatus() == PointChargeStatus.PROCESSING) {
            charge.fail(reason, Instant.now().truncatedTo(ChronoUnit.MICROS));
        }
        return ChargeResult.from(charge);
    }

    // 결과를 확정하지 못한 사유를 남긴다(PROCESSING 유지). 이미 다른 요청이 끝냈으면 그대로 둔다.
    @Transactional
    public ChargeResult noteUnresolved(Long chargeId, String reason) {
        PointCharge charge = lockCharge(chargeId);
        if (charge.getStatus() == PointChargeStatus.PROCESSING) {
            noteUnresolved(charge, reason);
        }
        return ChargeResult.from(charge);
    }

    // 미확정 사유를 남기고, 사유가 같아도 확인 시각(updated_at)을 갱신해 결과 확인 배치의 오래된 순 목록 뒤로 보낸다.
    private void noteUnresolved(PointCharge charge, String reason) {
        charge.noteUnresolved(reason);
        pointChargeRepository.touchUpdatedAt(charge.getId(), Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    // 결과 확인에 필요한 값(주문 ID·금액·결제키 원문)을 읽는다. PROCESSING이 아니면 확인할 것이 없다.
    // 결제키 원문은 승인 재요청에만 쓴다. 복호화할 수 없으면(암호화 키 교체 등) null로 두어 조회로 확정하는 길은 막지 않는다
    // (조회 결과의 결제키는 HMAC으로 대조한다).
    @Transactional(readOnly = true)
    public Optional<RecoveryTarget> recoveryTarget(Long chargeId) {
        return pointChargeRepository.findById(chargeId)
                .filter(charge -> charge.getStatus() == PointChargeStatus.PROCESSING)
                .map(charge -> new RecoveryTarget(charge.getId(), charge.getPgOrderId(), charge.getAmountHp(),
                        readablePaymentKey(charge)));
    }

    private String readablePaymentKey(PointCharge charge) {
        try {
            return paymentKeyCipher.decrypt(charge.getPgPaymentKey());
        } catch (IllegalStateException exception) {
            log.error("결제키 암호문을 복호화할 수 없다. 승인 재요청 없이 조회로만 확인한다: chargeId={}, orderId={}",
                    charge.getId(), charge.getPgOrderId());
            return null;
        }
    }

    // 충전 건의 현재 결과
    @Transactional(readOnly = true)
    public ChargeResult result(Long chargeId) {
        return ChargeResult.from(pointChargeRepository.findById(chargeId)
                .orElseThrow(() -> new IllegalStateException("충전 건이 없습니다. chargeId=" + chargeId)));
    }

    // 회원 자신의 충전 주문 결과. 다른 회원의 주문은 없는 주문과 같게 본다.
    @Transactional(readOnly = true)
    public ChargeResult resultOf(Long userId, String orderId) {
        Long walletId = walletRepository.findByUserId(userId)
                .map(Wallet::getId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.CHARGE_NOT_FOUND));
        return pointChargeRepository.findByPgOrderId(orderId)
                .filter(charge -> charge.getWalletId().equals(walletId))
                .map(ChargeResult::from)
                .orElseThrow(() -> new BillingException(BillingErrorCode.CHARGE_NOT_FOUND));
    }

    private PointCharge lockCharge(Long chargeId) {
        PointCharge charge = pointChargeRepository.findByIdForUpdate(chargeId)
                .orElseThrow(() -> new IllegalStateException("충전 건이 없습니다. chargeId=" + chargeId));
        // 락 조회는 이미 영속성 컨텍스트에 있던 엔티티를 바꾸지 않으므로 락을 잡은 뒤 최신 상태로 다시 읽는다
        entityManager.refresh(charge);
        return charge;
    }

    // 승인된 결제가 이 주문의 것이고 금액이 같은지 확인한다. 맞지 않으면 사유를, 맞으면 null을 돌려준다.
    private String mismatchOf(PointCharge charge, TossPayment payment) {
        if (!payment.isDone()) {
            return "APPROVED_STATUS_" + payment.status();
        }
        if (!charge.getPgOrderId().equals(payment.orderId())) {
            return "APPROVED_ORDER_ID_MISMATCH";
        }
        if (payment.paymentKey() == null || !charge.hasPaymentKeyHash(paymentKeyCipher.hash(payment.paymentKey()))) {
            return "APPROVED_PAYMENT_KEY_MISMATCH";
        }
        if (payment.totalAmount() == null || payment.totalAmount() != charge.getAmountHp().longValue()) {
            return "APPROVED_AMOUNT_MISMATCH:" + payment.totalAmount();
        }
        return null;
    }

    private static Claim replayOrConflict(PointCharge charge, String paymentKeyHash, Claim claim) {
        if (!charge.hasPaymentKeyHash(paymentKeyHash)) {
            throw new BillingException(BillingErrorCode.CHARGE_PAYMENT_KEY_CONFLICT);
        }
        return claim;
    }

    // 승인 요청 시작 판단: 처음 요청(Start), 같은 결제로 이미 확정됨(Replay), 같은 결제로 결과 확인 중(Recover)
    public sealed interface Claim {
        record Start(Long chargeId) implements Claim {
        }

        record Replay(ChargeResult result) implements Claim {
        }

        record Recover(Long chargeId) implements Claim {
        }
    }

    // 충전 확정 결과: 확정됨(이번 또는 이전 요청) 또는 승인 결과가 주문과 맞지 않아 충전하지 않음
    public sealed interface Completion {
        record Completed(ChargeResult result) implements Completion {
        }

        record Mismatch(String reason) implements Completion {
        }
    }

    // 결과 확인 대상. paymentKey는 복호화할 수 없으면 null이다
    public record RecoveryTarget(Long chargeId, String orderId, long amount, String paymentKey) {

        // 결제키 원문이 로그에 찍히지 않게 한다
        @Override
        public String toString() {
            return "RecoveryTarget[chargeId=" + chargeId + ", orderId=" + orderId + ", amount=" + amount + "]";
        }
    }
}
