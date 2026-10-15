package com.hapbang.lecture.fake;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.hapbang.global.exception.BaseErrorCode;
import com.hapbang.global.exception.BusinessException;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;
import com.hapbang.shared.billing.port.BillingPort;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 강의 모듈 테스트용 billing. 모듈 테스트 컨텍스트에는 billing 빈이 없으므로 shared {@link BillingPort}를 이 Fake로 채운다.
 * <ul>
 *     <li>회원별 잔액을 정하지 않으면 잔액은 충분하다. 정하면 보류할 때마다 줄고, 모자라면 409 {@code INSUFFICIENT_BALANCE}다.</li>
 *     <li>실제 billing처럼 호출한 쪽 트랜잭션을 따른다. 커밋된 보류·반환만 기록하고, 롤백되면 잔액을 되돌린다.</li>
 *     <li>보류가 없는 참조 대상은 반환할 수 없다. 반환액은 보류액이다({@link #overrideReleasedHp}로 바꿀 수 있다).</li>
 *     <li>보류가 없는 참조 대상은 지급할 수 없다. 지급액은 보류액이다({@link #overridePaidHp}로 바꿀 수 있다).
 *     보류 없이 저장한 Fixture 주문은 {@link #assumeHeld}로 보류가 있다고 정한다.</li>
 * </ul>
 * 컨텍스트가 테스트 사이에 재사용되므로 테스트마다 {@link #reset()}한다.
 */
@Component
public class FakeBillingPort implements BillingPort {

    private final Map<Long, Long> balances = new ConcurrentHashMap<>();
    private final Map<Long, HoldCommand> heldItems = new ConcurrentHashMap<>();
    private final List<HoldCommand> holds = new CopyOnWriteArrayList<>();
    private final List<ReleaseCommand> releases = new CopyOnWriteArrayList<>();
    private final List<PayoutCommand> payouts = new CopyOnWriteArrayList<>();
    /** 지급한 참조 대상 ID → 지급 요청 ID (같은 대상을 다른 요청 ID로 다시 지급하지 못하게 한다) */
    private final Map<Long, String> paidItems = new ConcurrentHashMap<>();
    private volatile Long releasedHpOverride;
    private volatile Long paidHpOverride;
    private volatile Long failingPayoutReferenceId;
    private volatile FakeBillingErrorCode payoutFailureCode;
    private volatile CountDownLatch releaseGate;
    private volatile Duration releaseGateTimeout;

    public void reset() {
        balances.clear();
        heldItems.clear();
        holds.clear();
        releases.clear();
        payouts.clear();
        paidItems.clear();
        releasedHpOverride = null;
        paidHpOverride = null;
        failingPayoutReferenceId = null;
        payoutFailureCode = null;
        releaseGate = null;
    }

    public void setBalance(Long userId, long balanceHp) {
        balances.put(userId, balanceHp);
    }

    /** 다음 반환부터 이 금액을 반환했다고 응답한다 (반환 결과 검증용) */
    public void overrideReleasedHp(long releasedHp) {
        this.releasedHpOverride = releasedHp;
    }

    /** 다음 지급부터 이 금액을 지급했다고 응답한다 (지급 결과 검증용) */
    public void overridePaidHp(long paidHp) {
        this.paidHpOverride = paidHp;
    }

    /** 이 참조 대상의 지급을 지갑 없음({@code WALLET_NOT_FOUND}, 일시 실패)으로 거절한다({@code null}이면 해제) */
    public void failPayoutFor(Long referenceId) {
        failPayoutFor(referenceId, FakeBillingErrorCode.WALLET_NOT_FOUND);
    }

    /** 이 참조 대상의 지급을 이 billing 업무 오류로 거절한다({@code null}이면 해제). 오류 분류별 지급 배치 확인용 (D54) */
    public void failPayoutFor(Long referenceId, FakeBillingErrorCode code) {
        this.payoutFailureCode = code;
        this.failingPayoutReferenceId = referenceId;
    }

    /** 보류 없이 저장한 주문 항목에 보류가 있다고 정한다 (Fixture 주문을 지급할 때) */
    public void assumeHeld(Long userId, Long orderItemId, long amountHp) {
        heldItems.put(orderItemId, new HoldCommand(userId, BillingReferenceType.LECTURE_ORDER_ITEM, orderItemId,
                amountHp));
    }

    /**
     * 반환 요청이 {@code parties}개 모일 때까지(최대 {@code timeout}) 반환을 멈춘다. 동시 요청이 서로의 커밋 전에
     * 같은 상태를 읽게 만들어 경쟁을 재현한다. 호출한 쪽이 잠금으로 직렬화하면 앞 요청은 시간이 지나 그대로 진행한다.
     */
    public void holdReleasesUntil(int parties, Duration timeout) {
        this.releaseGateTimeout = timeout;
        this.releaseGate = new CountDownLatch(parties);
    }

    /** 커밋된 보류 */
    public List<HoldCommand> holds() {
        return List.copyOf(holds);
    }

    /** 커밋된 반환 */
    public List<ReleaseCommand> releases() {
        return List.copyOf(releases);
    }

    /** 커밋된 지급 */
    public List<PayoutCommand> payouts() {
        return List.copyOf(payouts);
    }

    @Override
    public HoldResultDto hold(HoldCommand command) {
        Long balance = balances.get(command.userId());
        if (balance != null) {
            if (balance < command.amountHp()) {
                throw new BusinessException(FakeBillingErrorCode.INSUFFICIENT_BALANCE);
            }
            balances.put(command.userId(), balance - command.amountHp());
        }
        heldItems.put(command.referenceId(), command);
        onCompletion(() -> holds.add(command), () -> {
            heldItems.remove(command.referenceId());
            balances.computeIfPresent(command.userId(), (userId, current) -> current + command.amountHp());
        });
        return new HoldResultDto(command.referenceType(), command.referenceId(), command.amountHp(), false);
    }

    @Override
    public ReleaseResultDto release(ReleaseCommand command) {
        HoldCommand hold = heldItems.get(command.referenceId());
        if (hold == null || !hold.userId().equals(command.userId())) {
            throw new IllegalStateException("보류가 없습니다: " + command.referenceId());
        }
        awaitReleaseGate();
        long releasedHp = releasedHpOverride != null ? releasedHpOverride : hold.amountHp();
        onCompletion(() -> releases.add(command), () -> {
        });
        return new ReleaseResultDto(command.referenceType(), command.referenceId(), command.releaseRequestId(),
                releasedHp, hold.amountHp() - releasedHp, false);
    }

    @Override
    public PayoutResultDto payout(PayoutCommand command) {
        HoldCommand hold = heldItems.get(command.referenceId());
        if (hold == null || !hold.userId().equals(command.payerUserId())) {
            throw new IllegalStateException("보류가 없습니다: " + command.referenceId());
        }
        if (command.referenceId().equals(failingPayoutReferenceId)) {
            throw new BusinessException(payoutFailureCode);
        }
        String paidRequestId = paidItems.putIfAbsent(command.referenceId(), command.payoutRequestId());
        if (paidRequestId != null && !paidRequestId.equals(command.payoutRequestId())) {
            throw new BusinessException(FakeBillingErrorCode.PAYOUT_ALREADY_SETTLED);
        }
        long paidHp = paidHpOverride != null ? paidHpOverride : hold.amountHp();
        if (paidRequestId == null) {
            onCompletion(() -> payouts.add(command), () -> paidItems.remove(command.referenceId()));
        }
        return new PayoutResultDto(command.referenceType(), command.referenceId(), command.payoutRequestId(),
                command.receiverUserId(), command.sourceType(), command.sourceId(), paidHp, hold.amountHp() - paidHp,
                paidRequestId != null);
    }

    @Override
    public PenaltyResultDto settlePenalty(PenaltyCommand command) {
        throw new UnsupportedOperationException("강의는 위약금을 정산하지 않는다");
    }

    private void awaitReleaseGate() {
        CountDownLatch gate = releaseGate;
        if (gate == null) {
            return;
        }
        gate.countDown();
        try {
            gate.await(releaseGateTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void onCompletion(Runnable afterCommit, Runnable afterRollback) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            afterCommit.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    afterCommit.run();
                } else {
                    afterRollback.run();
                }
            }
        });
    }

    @Getter
    @RequiredArgsConstructor
    public enum FakeBillingErrorCode implements BaseErrorCode {
        INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "HP 잔액이 부족합니다."),
        WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "지갑을 찾을 수 없습니다."),
        PAYOUT_ALREADY_SETTLED(HttpStatus.CONFLICT, "이미 지급한 대상입니다."),
        HOLD_NOT_FOUND(HttpStatus.NOT_FOUND, "HP 보류를 찾을 수 없습니다."),
        HOLD_ALREADY_CLOSED(HttpStatus.CONFLICT, "남은 보류 HP가 없어 더 처리할 수 없습니다."),
        IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "같은 작업 키로 다른 내용의 원장 기록이 요청되었습니다."),
        HOLD_STATE_INCONSISTENT(HttpStatus.INTERNAL_SERVER_ERROR, "HP 처리 중 오류가 발생했습니다.");

        private final HttpStatus status;
        private final String message;
    }
}
