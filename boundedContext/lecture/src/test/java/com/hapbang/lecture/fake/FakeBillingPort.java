package com.hapbang.lecture.fake;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.hapbang.global.exception.BaseErrorCode;
import com.hapbang.global.exception.BusinessException;
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
 * </ul>
 * 컨텍스트가 테스트 사이에 재사용되므로 테스트마다 {@link #reset()}한다.
 */
@Component
public class FakeBillingPort implements BillingPort {

    private final Map<Long, Long> balances = new ConcurrentHashMap<>();
    private final Map<Long, HoldCommand> heldItems = new ConcurrentHashMap<>();
    private final List<HoldCommand> holds = new CopyOnWriteArrayList<>();
    private final List<ReleaseCommand> releases = new CopyOnWriteArrayList<>();
    private volatile Long releasedHpOverride;

    public void reset() {
        balances.clear();
        heldItems.clear();
        holds.clear();
        releases.clear();
        releasedHpOverride = null;
    }

    public void setBalance(Long userId, long balanceHp) {
        balances.put(userId, balanceHp);
    }

    /** 다음 반환부터 이 금액을 반환했다고 응답한다 (반환 결과 검증용) */
    public void overrideReleasedHp(long releasedHp) {
        this.releasedHpOverride = releasedHp;
    }

    /** 커밋된 보류 */
    public List<HoldCommand> holds() {
        return List.copyOf(holds);
    }

    /** 커밋된 반환 */
    public List<ReleaseCommand> releases() {
        return List.copyOf(releases);
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
        long releasedHp = releasedHpOverride != null ? releasedHpOverride : hold.amountHp();
        onCompletion(() -> releases.add(command), () -> {
        });
        return new ReleaseResultDto(command.referenceType(), command.referenceId(), command.releaseRequestId(),
                releasedHp, hold.amountHp() - releasedHp, false);
    }

    @Override
    public PayoutResultDto payout(PayoutCommand command) {
        throw new UnsupportedOperationException("강의 지급은 #150에서 연동한다");
    }

    @Override
    public PenaltyResultDto settlePenalty(PenaltyCommand command) {
        throw new UnsupportedOperationException("강의는 위약금을 정산하지 않는다");
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
        INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "HP 잔액이 부족합니다.");

        private final HttpStatus status;
        private final String message;
    }
}
