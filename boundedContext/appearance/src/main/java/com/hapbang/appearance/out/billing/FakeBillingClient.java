package com.hapbang.appearance.out.billing;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Billing API 계약이 확정되기 전까지 쓰는 메모리 구현. 실제 Billing처럼 잔액·보류·멱등 처리를 흉내 낸다.
 * <ul>
 *     <li>{@code appearance.billing.mode=fake}(기본값)일 때만 빈으로 등록된다.</li>
 *     <li>모든 회원은 {@link #DEFAULT_BALANCE}만큼 잔액을 가진 것으로 시작한다. 테스트에서는 {@link #setBalance}로 바꾼다.</li>
 *     <li>보류보다 많이 반환·지급하려 하면 예외를 던진다. 출연 쪽 금액 계산 실수를 테스트에서 바로 잡기 위해서다.</li>
 *     <li>테스트에서 {@link #setUnavailable}로 Billing 무응답({@link BillingUnavailableException})을 흉내 낼 수 있다.</li>
 *     <li>앱이 재시작되면 기록이 모두 사라진다.</li>
 * </ul>
 */
// TODO(billing): HTTP 구현체가 생기면 운영·개발 서버에서는 mode 값을 바꿔 이 구현을 끈다.
@Component
@ConditionalOnProperty(prefix = "appearance.billing", name = "mode", havingValue = "fake", matchIfMissing = true)
public class FakeBillingClient implements BillingClient {

    public static final long DEFAULT_BALANCE = 10_000_000L;

    private final Map<Long, Long> balances = new HashMap<>();
    private final Map<HoldKey, Long> holds = new HashMap<>();
    private final Map<String, Object> processedResults = new HashMap<>();
    private boolean unavailable;

    @Override
    public synchronized HoldResult hold(HoldCommand command) {
        requireAvailable();
        HoldResult processed = findProcessed(command.idempotencyKey(), HoldResult.class);
        if (processed != null) {
            return processed;
        }

        long balance = balanceOf(command.hostId());
        HoldResult result;
        if (balance < command.amount()) {
            result = new HoldResult(HoldStatus.INSUFFICIENT_BALANCE, 0);
        } else {
            balances.put(command.hostId(), balance - command.amount());
            long held = holds.merge(new HoldKey(command.refType(), command.refId()), command.amount(), Long::sum);
            result = new HoldResult(HoldStatus.HELD, held);
        }
        processedResults.put(command.idempotencyKey(), result);
        return result;
    }

    @Override
    public synchronized Result release(ReleaseCommand command) {
        requireAvailable();
        Result processed = findProcessed(command.idempotencyKey(), Result.class);
        if (processed != null) {
            return processed;
        }

        HoldKey key = new HoldKey(command.refType(), command.refId());
        requireHeld(key, command.amount());
        Result result = new Result(moveFromHold(key, command.hostId(), command.amount()));
        processedResults.put(command.idempotencyKey(), result);
        return result;
    }

    @Override
    public synchronized Result payout(PayoutCommand command) {
        requireAvailable();
        Result processed = findProcessed(command.idempotencyKey(), Result.class);
        if (processed != null) {
            return processed;
        }

        HoldKey key = new HoldKey(command.refType(), command.refId());
        requireHeld(key, command.amount());
        Result result = new Result(moveFromHold(key, command.guestId(), command.amount()));
        processedResults.put(command.idempotencyKey(), result);
        return result;
    }

    @Override
    public synchronized Result settle(SettleCommand command) {
        requireAvailable();
        Result processed = findProcessed(command.idempotencyKey(), Result.class);
        if (processed != null) {
            return processed;
        }

        HoldKey key = new HoldKey(command.refType(), command.refId());
        // 전부 성공 또는 전부 실패: 적용하기 전에 총액부터 확인한다.
        requireHeld(key, command.totalAmount());
        long remaining = 0;
        for (SettleItem item : command.items()) {
            remaining = moveFromHold(key, item.memberId(), item.amount());
        }
        Result result = new Result(remaining);
        processedResults.put(command.idempotencyKey(), result);
        return result;
    }

    /**
     * 테스트용: 회원의 잔액을 정한다.
     */
    public synchronized void setBalance(long memberId, long amount) {
        balances.put(memberId, amount);
    }

    /**
     * 테스트용: 회원의 현재 잔액.
     */
    public synchronized long balanceOf(long memberId) {
        return balances.getOrDefault(memberId, DEFAULT_BALANCE);
    }

    /**
     * 테스트용: 대상(요청서·모집글)에 남아 있는 보류 금액.
     */
    public synchronized long heldAmount(RefType refType, long refId) {
        return holds.getOrDefault(new HoldKey(refType, refId), 0L);
    }

    /**
     * 테스트용: true면 모든 요청에 응답하지 않는다({@link BillingUnavailableException}). 아무것도 처리하지 않는다.
     */
    public synchronized void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    /**
     * 테스트용: 모든 기록을 지우고 응답하는 상태로 되돌린다.
     */
    public synchronized void reset() {
        balances.clear();
        holds.clear();
        processedResults.clear();
        unavailable = false;
    }

    private void requireAvailable() {
        if (unavailable) {
            throw new BillingUnavailableException("Billing이 응답하지 않습니다 (Fake 무응답 설정).");
        }
    }

    private <T> T findProcessed(String idempotencyKey, Class<T> type) {
        Object processed = processedResults.get(idempotencyKey);
        if (processed == null) {
            return null;
        }
        if (!type.isInstance(processed)) {
            throw new IllegalStateException("다른 종류의 요청에 이미 사용한 멱등 키입니다: " + idempotencyKey);
        }
        return type.cast(processed);
    }

    private void requireHeld(HoldKey key, long amount) {
        long held = holds.getOrDefault(key, 0L);
        if (held < amount) {
            throw new IllegalStateException(
                    "보류 금액보다 많이 처리할 수 없습니다. 보류: " + held + ", 요청: " + amount + ", 대상: " + key);
        }
    }

    /**
     * 보류에서 금액을 빼서 받는 사람 잔액에 더하고, 남은 보류 금액을 돌려준다.
     */
    private long moveFromHold(HoldKey key, long receiverId, long amount) {
        long remaining = holds.get(key) - amount;
        holds.put(key, remaining);
        balances.put(receiverId, balanceOf(receiverId) + amount);
        return remaining;
    }

    private record HoldKey(RefType refType, long refId) {
    }
}
