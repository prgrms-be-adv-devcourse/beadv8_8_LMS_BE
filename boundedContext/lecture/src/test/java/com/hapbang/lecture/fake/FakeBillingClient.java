package com.hapbang.lecture.fake;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.hapbang.lecture.out.billing.BillingCallResult;
import com.hapbang.lecture.out.billing.BillingClient;
import com.hapbang.shared.billing.domain.BillingFailureCode;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;

/**
 * 강의 모듈 테스트용 billing. 모듈 테스트 컨텍스트에는 billing 빈이 없으므로 강의 {@link BillingClient}를 이 Fake로 채운다.
 * <ul>
 *     <li>billing이 처리하면 {@link BillingCallResult.Succeeded}, 처리하지 못하면 {@link BillingCallResult.Failed}를 돌려준다.
 *     Fake가 스스로 만드는 실패의 메시지는 billing 오류 코드의 기본 메시지와 같다.</li>
 *     <li>회원별 잔액을 정하지 않으면 잔액은 충분하다. 정하면 보류할 때마다 줄고, 모자라면 {@code INSUFFICIENT_BALANCE}다.</li>
 *     <li>실제 billing처럼 호출한 쪽 트랜잭션을 따른다. 커밋된 보류·반환만 기록하고, 롤백되면 잔액을 되돌린다.</li>
 *     <li>보류가 없는 참조 대상은 반환할 수 없다(테스트 준비 오류라 {@code IllegalStateException}). 반환액은 보류액이다({@link #overrideReleasedHp}로 바꿀 수 있다).</li>
 *     <li>보류가 없는 참조 대상은 지급할 수 없다(같은 이유로 {@code IllegalStateException}). 지급액은 보류액이다({@link #overridePaidHp}로 바꿀 수 있다).
 *     보류 없이 저장한 Fixture 주문은 {@link #assumeHeld}로 보류가 있다고 정한다.</li>
 * </ul>
 * 컨텍스트가 테스트 사이에 재사용되므로 테스트마다 {@link #reset()}한다.
 */
@Component
public class FakeBillingClient implements BillingClient {

    private static final String INSUFFICIENT_BALANCE_MESSAGE = "HP 잔액이 부족합니다.";
    private static final String WALLET_NOT_FOUND_MESSAGE = "지갑을 찾을 수 없습니다.";
    private static final String PAYOUT_ALREADY_SETTLED_MESSAGE = "같은 출연 계약·주문 항목(또는 같은 출연 요청)에 이미 지급했습니다.";

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
    private volatile BillingCallResult.Failed<PayoutResultDto> payoutFailure;
    private volatile Long throwingPayoutReferenceId;
    private volatile RuntimeException payoutException;
    private volatile Long failingReleaseReferenceId;
    private volatile BillingCallResult.Failed<ReleaseResultDto> releaseFailure;
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
        payoutFailure = null;
        throwingPayoutReferenceId = null;
        payoutException = null;
        failingReleaseReferenceId = null;
        releaseFailure = null;
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

    /** 이 참조 대상의 지급을 지갑 없음({@code WALLET_NOT_FOUND}, 일시 실패)으로 실패시킨다({@code null}이면 해제) */
    public void failPayoutFor(Long referenceId) {
        failPayoutFor(referenceId, BillingFailureCode.WALLET_NOT_FOUND, WALLET_NOT_FOUND_MESSAGE);
    }

    /** 이 참조 대상의 지급을 이 billing 실패로 돌려준다({@code null}이면 해제). 오류 분류별 지급 배치 확인용 (D54) */
    public void failPayoutFor(Long referenceId, BillingFailureCode code, String detail) {
        this.payoutFailure = new BillingCallResult.Failed<>(code, detail);
        this.failingPayoutReferenceId = referenceId;
    }

    /** 이 참조 대상의 지급에서 결과를 받지 못한 실패(예외)를 던진다({@code null}이면 해제) */
    public void throwOnPayout(Long referenceId, RuntimeException exception) {
        this.payoutException = exception;
        this.throwingPayoutReferenceId = referenceId;
    }

    /** 이 참조 대상의 반환을 이 billing 실패로 돌려준다({@code null}이면 해제) */
    public void failReleaseFor(Long referenceId, BillingFailureCode code, String detail) {
        this.releaseFailure = new BillingCallResult.Failed<>(code, detail);
        this.failingReleaseReferenceId = referenceId;
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
    public BillingCallResult<HoldResultDto> hold(HoldCommand command) {
        Long balance = balances.get(command.userId());
        if (balance != null) {
            if (balance < command.amountHp()) {
                return new BillingCallResult.Failed<>(BillingFailureCode.INSUFFICIENT_BALANCE,
                        INSUFFICIENT_BALANCE_MESSAGE);
            }
            balances.put(command.userId(), balance - command.amountHp());
        }
        heldItems.put(command.referenceId(), command);
        onCompletion(() -> holds.add(command), () -> {
            heldItems.remove(command.referenceId());
            balances.computeIfPresent(command.userId(), (userId, current) -> current + command.amountHp());
        });
        return new BillingCallResult.Succeeded<>(new HoldResultDto(command.referenceType(), command.referenceId(),
                command.amountHp(), false));
    }

    @Override
    public BillingCallResult<ReleaseResultDto> release(ReleaseCommand command) {
        HoldCommand hold = heldItems.get(command.referenceId());
        if (hold == null || !hold.userId().equals(command.userId())) {
            throw new IllegalStateException("보류가 없습니다: " + command.referenceId());
        }
        awaitReleaseGate();
        if (command.referenceId().equals(failingReleaseReferenceId)) {
            return releaseFailure;
        }
        long releasedHp = releasedHpOverride != null ? releasedHpOverride : hold.amountHp();
        onCompletion(() -> releases.add(command), () -> {
        });
        return new BillingCallResult.Succeeded<>(new ReleaseResultDto(command.referenceType(), command.referenceId(),
                command.releaseRequestId(), releasedHp, hold.amountHp() - releasedHp, false));
    }

    @Override
    public BillingCallResult<PayoutResultDto> payout(PayoutCommand command) {
        HoldCommand hold = heldItems.get(command.referenceId());
        if (hold == null || !hold.userId().equals(command.payerUserId())) {
            throw new IllegalStateException("보류가 없습니다: " + command.referenceId());
        }
        if (command.referenceId().equals(throwingPayoutReferenceId)) {
            throw payoutException;
        }
        if (command.referenceId().equals(failingPayoutReferenceId)) {
            return payoutFailure;
        }
        String paidRequestId = paidItems.putIfAbsent(command.referenceId(), command.payoutRequestId());
        if (paidRequestId != null && !paidRequestId.equals(command.payoutRequestId())) {
            return new BillingCallResult.Failed<>(BillingFailureCode.PAYOUT_ALREADY_SETTLED,
                    PAYOUT_ALREADY_SETTLED_MESSAGE);
        }
        long paidHp = paidHpOverride != null ? paidHpOverride : hold.amountHp();
        if (paidRequestId == null) {
            onCompletion(() -> payouts.add(command), () -> paidItems.remove(command.referenceId()));
        }
        return new BillingCallResult.Succeeded<>(new PayoutResultDto(command.referenceType(), command.referenceId(),
                command.payoutRequestId(), command.receiverUserId(), command.sourceType(), command.sourceId(), paidHp,
                hold.amountHp() - paidHp, paidRequestId != null));
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
}
