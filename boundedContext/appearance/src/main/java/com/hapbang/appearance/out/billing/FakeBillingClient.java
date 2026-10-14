package com.hapbang.appearance.out.billing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;

/**
 * 외부 연동 교체 전까지 쓰는 메모리 구현. Billing 구현(보류·반환·지급·위약금 UseCase)의 규칙을 흉내 낸다.
 * <ul>
 *     <li>{@code appearance.billing.mode=fake}(기본값)일 때만 빈으로 등록된다.</li>
 *     <li>보류는 참조 대상당 1건. 같은 회원·같은 금액으로 다시 보내면 처음 보류를 돌려주고({@code replayed=true}),
 *     다른 회원·다른 금액이면 {@code IDEMPOTENCY_KEY_CONFLICT}.</li>
 *     <li>반환·지급·위약금은 요청 ID(반환 요청 ID·지급 요청 ID·취소 ID)로 구분한다. 같은 요청 ID로 다시 보내면 처음 결과를 돌려준다.</li>
 *     <li>거절은 Billing과 같은 코드·메시지의 {@link FakeBillingException}으로 알린다 (잔액 부족, 남은 보류 초과, 이중 지급 등).</li>
 *     <li>모든 회원은 지갑과 {@link #DEFAULT_BALANCE}만큼 잔액을 가진 것으로 시작한다. 테스트에서는 {@link #setBalance},
 *     {@link #removeWallet}로 바꾼다.</li>
 *     <li>테스트에서 {@link #setUnavailable}로 처리 여부를 알 수 없는 실패({@link BillingUnavailableException})를 흉내 낼 수 있다.</li>
 * </ul>
 * Billing과 다른 점: 충전 HP·수익 HP를 나누지 않고 잔액 하나로 다룬다. 앱이 재시작되면 기록이 모두 사라진다.
 */
// TODO(billing): 외부 연동 교체 단계에서 BillingPort 구현체로 바꾸고, 운영·개발 서버에서는 mode 값을 바꿔 이 구현을 끈다.
@Component
@ConditionalOnProperty(prefix = "appearance.billing", name = "mode", havingValue = "fake", matchIfMissing = true)
public class FakeBillingClient implements BillingClient {

    public static final long DEFAULT_BALANCE = 10_000_000L;

    private final Map<Long, Long> balances = new HashMap<>();
    private final Set<Long> membersWithoutWallet = new HashSet<>();
    private final Map<HoldKey, Hold> holds = new HashMap<>();
    private final Map<RequestKey, Object> processedResults = new HashMap<>();
    /** 이미 지급(정상 지급·위약금)한 지급 원천 */
    private final Set<SourceKey> settledSources = new HashSet<>();
    /** 지급이 한 번이라도 있었던 보류 (출연 요청 보류는 출연 계약이 하나뿐이라 지급도 한 번뿐) */
    private final Set<HoldKey> settledHolds = new HashSet<>();
    private boolean unavailable;

    @Override
    public synchronized HoldResultDto hold(HoldCommand command) {
        requireAvailable();
        requirePositive(command.amountHp());
        requireWallet(command.userId());

        HoldKey key = new HoldKey(command.referenceType(), command.referenceId());
        Hold existing = holds.get(key);
        if (existing != null) {
            if (!existing.ownerId.equals(command.userId()) || existing.originalHp != command.amountHp()) {
                throw new FakeBillingException(FakeBillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return new HoldResultDto(key.type(), key.id(), existing.originalHp, true);
        }

        long balance = balanceOf(command.userId());
        if (balance < command.amountHp()) {
            // 보류를 만들지 않으므로, 잔액을 채운 뒤 다시 보내면 새로 보류한다.
            throw new FakeBillingException(FakeBillingErrorCode.INSUFFICIENT_BALANCE);
        }
        balances.put(command.userId(), balance - command.amountHp());
        holds.put(key, new Hold(command.userId(), command.amountHp()));
        return new HoldResultDto(key.type(), key.id(), command.amountHp(), false);
    }

    @Override
    public synchronized ReleaseResultDto release(ReleaseCommand command) {
        requireAvailable();
        if (!command.isFull()) {
            requirePositive(command.amountHp());
        }
        requireWallet(command.userId());
        HoldKey holdKey = new HoldKey(command.referenceType(), command.referenceId());
        Hold hold = getOwnedHold(holdKey, command.userId());

        RequestKey requestKey = new RequestKey(holdKey, Action.RELEASE, command.releaseRequestId());
        ReleaseResultDto processed = (ReleaseResultDto) processedResults.get(requestKey);
        if (processed != null) {
            if (!command.isFull() && processed.releasedHp() != command.amountHp()) {
                throw new FakeBillingException(FakeBillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return new ReleaseResultDto(holdKey.type(), holdKey.id(), command.releaseRequestId(),
                    processed.releasedHp(), hold.remainingHp, true);
        }

        requireOpen(hold);
        long amount = command.isFull() ? hold.remainingHp : command.amountHp();
        if (amount > hold.remainingHp) {
            throw new FakeBillingException(FakeBillingErrorCode.RELEASE_AMOUNT_EXCEEDS_HELD);
        }
        moveFromHold(hold, command.userId(), amount);
        ReleaseResultDto result = new ReleaseResultDto(holdKey.type(), holdKey.id(), command.releaseRequestId(),
                amount, hold.remainingHp, false);
        processedResults.put(requestKey, result);
        return result;
    }

    @Override
    public synchronized PayoutResultDto payout(PayoutCommand command) {
        requireAvailable();
        if (!command.isFull()) {
            requirePositive(command.amountHp());
        }
        requireWallet(command.payerUserId());
        requireWallet(command.receiverUserId());
        requireNotPayer(command.payerUserId(), command.receiverUserId());
        HoldKey holdKey = new HoldKey(command.referenceType(), command.referenceId());
        Hold hold = getOwnedHold(holdKey, command.payerUserId());
        SourceKey source = new SourceKey(command.sourceType(), command.sourceId());

        RequestKey requestKey = new RequestKey(holdKey, Action.PAYOUT, command.payoutRequestId());
        PayoutResultDto processed = (PayoutResultDto) processedResults.get(requestKey);
        if (processed != null) {
            boolean sameRequest = processed.receiverUserId().equals(command.receiverUserId())
                    && processed.sourceType() == command.sourceType()
                    && processed.sourceId().equals(command.sourceId())
                    && (command.isFull() || processed.paidHp() == command.amountHp());
            if (!sameRequest) {
                throw new FakeBillingException(FakeBillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return new PayoutResultDto(holdKey.type(), holdKey.id(), command.payoutRequestId(),
                    command.receiverUserId(), source.type(), source.id(), processed.paidHp(), hold.remainingHp, true);
        }

        requireOpen(hold);
        requireMatchingSource(holdKey, source);
        requireNotSettled(holdKey, source);
        long amount = command.isFull() ? hold.remainingHp : command.amountHp();
        if (amount > hold.remainingHp) {
            throw new FakeBillingException(FakeBillingErrorCode.PAYOUT_AMOUNT_EXCEEDS_HELD);
        }
        moveFromHold(hold, command.receiverUserId(), amount);
        markSettled(holdKey, source);
        PayoutResultDto result = new PayoutResultDto(holdKey.type(), holdKey.id(), command.payoutRequestId(),
                command.receiverUserId(), source.type(), source.id(), amount, hold.remainingHp, false);
        processedResults.put(requestKey, result);
        return result;
    }

    @Override
    public synchronized PenaltyResultDto settlePenalty(PenaltyCommand command) {
        requireAvailable();
        if (command.releaseAmountHp() < 0 || command.payoutAmountHp() < 0) {
            throw new FakeBillingException(FakeBillingErrorCode.INVALID_POINT_AMOUNT);
        }
        if (command.payoutAmountHp() == 0) {
            throw new FakeBillingException(FakeBillingErrorCode.PENALTY_AMOUNT_ZERO);
        }
        requireWallet(command.payerUserId());
        requireWallet(command.receiverUserId());
        requireNotPayer(command.payerUserId(), command.receiverUserId());
        HoldKey holdKey = new HoldKey(command.referenceType(), command.referenceId());
        Hold hold = getOwnedHold(holdKey, command.payerUserId());
        SourceKey source = new SourceKey(command.sourceType(), command.sourceId());

        RequestKey requestKey = new RequestKey(holdKey, Action.PENALTY, command.cancellationId());
        PenaltyResultDto processed = (PenaltyResultDto) processedResults.get(requestKey);
        if (processed != null) {
            boolean sameRequest = processed.receiverUserId().equals(command.receiverUserId())
                    && processed.sourceType() == command.sourceType()
                    && processed.sourceId().equals(command.sourceId())
                    && processed.releasedHp() == command.releaseAmountHp()
                    && processed.paidHp() == command.payoutAmountHp();
            if (!sameRequest) {
                throw new FakeBillingException(FakeBillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return new PenaltyResultDto(holdKey.type(), holdKey.id(), command.cancellationId(),
                    command.receiverUserId(), source.type(), source.id(), processed.releasedHp(), processed.paidHp(),
                    hold.remainingHp, true);
        }

        requireOpen(hold);
        // 정산 기준액(반환액 + 위약금)은 출연이 정한 그 출연 몫이다. 남은 보류를 넘을 수 없다.
        if (command.releaseAmountHp() + command.payoutAmountHp() > hold.remainingHp) {
            throw new FakeBillingException(FakeBillingErrorCode.PENALTY_BASE_EXCEEDS_HELD);
        }
        requireMatchingSource(holdKey, source);
        requireNotSettled(holdKey, source);
        // 반환과 위약금 지급은 함께 성공한다 (검사를 모두 마친 뒤에 옮긴다).
        moveFromHold(hold, command.payerUserId(), command.releaseAmountHp());
        moveFromHold(hold, command.receiverUserId(), command.payoutAmountHp());
        markSettled(holdKey, source);
        PenaltyResultDto result = new PenaltyResultDto(holdKey.type(), holdKey.id(), command.cancellationId(),
                command.receiverUserId(), source.type(), source.id(), command.releaseAmountHp(),
                command.payoutAmountHp(), hold.remainingHp, false);
        processedResults.put(requestKey, result);
        return result;
    }

    /**
     * 테스트용: 회원의 잔액을 정한다.
     */
    public synchronized void setBalance(long memberId, long amount) {
        balances.put(memberId, amount);
    }

    /**
     * 테스트용: 회원의 현재 잔액 (보류된 HP는 빠진 금액).
     */
    public synchronized long balanceOf(long memberId) {
        return balances.getOrDefault(memberId, DEFAULT_BALANCE);
    }

    /**
     * 테스트용: 회원의 지갑이 없는 상태로 만든다 ({@code WALLET_NOT_FOUND}).
     */
    public synchronized void removeWallet(long memberId) {
        membersWithoutWallet.add(memberId);
    }

    /**
     * 테스트용: 대상(요청서·모집글)에 남아 있는 보류 금액. 보류가 없으면 0.
     */
    public synchronized long heldAmount(BillingReferenceType referenceType, long referenceId) {
        Hold hold = holds.get(new HoldKey(referenceType, referenceId));
        if (hold == null) {
            return 0;
        }
        return hold.remainingHp;
    }

    /**
     * 테스트용: true면 모든 요청을 처리하지 않고 {@link BillingUnavailableException}을 던진다.
     */
    public synchronized void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    /**
     * 테스트용: 모든 기록을 지우고 처음 상태로 되돌린다.
     */
    public synchronized void reset() {
        balances.clear();
        membersWithoutWallet.clear();
        holds.clear();
        processedResults.clear();
        settledSources.clear();
        settledHolds.clear();
        unavailable = false;
    }

    private void requireAvailable() {
        if (unavailable) {
            throw new BillingUnavailableException("Billing 처리 결과를 알 수 없습니다 (Fake 무응답 설정).");
        }
    }

    private void requirePositive(long amountHp) {
        if (amountHp <= 0) {
            throw new FakeBillingException(FakeBillingErrorCode.INVALID_POINT_AMOUNT);
        }
    }

    private void requireWallet(Long memberId) {
        if (membersWithoutWallet.contains(memberId)) {
            throw new FakeBillingException(FakeBillingErrorCode.WALLET_NOT_FOUND);
        }
    }

    /** 같은 지갑 안의 이동은 충전 HP를 수익 HP로 바꾸는 것이 되므로 Billing이 거절한다 */
    private void requireNotPayer(Long payerId, Long receiverId) {
        if (payerId.equals(receiverId)) {
            throw new FakeBillingException(FakeBillingErrorCode.PAYOUT_TO_PAYER);
        }
    }

    private Hold getOwnedHold(HoldKey key, Long ownerId) {
        Hold hold = holds.get(key);
        if (hold == null) {
            throw new FakeBillingException(FakeBillingErrorCode.HOLD_NOT_FOUND);
        }
        if (!hold.ownerId.equals(ownerId)) {
            throw new FakeBillingException(FakeBillingErrorCode.HOLD_OWNER_MISMATCH);
        }
        return hold;
    }

    /** 남은 보류가 없으면 새 요청으로는 더 처리하지 않는다 (같은 요청의 재전송은 이 확인 전에 처음 결과를 돌려준다) */
    private void requireOpen(Hold hold) {
        if (hold.remainingHp == 0) {
            throw new FakeBillingException(FakeBillingErrorCode.HOLD_ALREADY_CLOSED);
        }
    }

    /** 출연 요청 보류는 출연 계약(APPEARANCE_REQUEST), 출연 모집 보류는 게스트별 출연(RECRUIT_APPEARANCE)에 지급한다 */
    private void requireMatchingSource(HoldKey holdKey, SourceKey source) {
        boolean matched = switch (holdKey.type()) {
            case APPEARANCE_REQUEST -> source.type() == SettlementSourceType.APPEARANCE_REQUEST;
            case APPEARANCE_RECRUIT -> source.type() == SettlementSourceType.RECRUIT_APPEARANCE;
            case LECTURE_ORDER_ITEM -> source.type() == SettlementSourceType.LECTURE_ORDER_ITEM
                    && source.id().equals(holdKey.id());
            default -> false;
        };
        if (!matched) {
            throw new FakeBillingException(FakeBillingErrorCode.PAYOUT_SOURCE_MISMATCH);
        }
    }

    /** 한 지급 원천에는 정상 지급·위약금을 합쳐 한 번만 지급한다. 출연 요청 보류는 지급 자체가 한 번뿐이다 */
    private void requireNotSettled(HoldKey holdKey, SourceKey source) {
        boolean settled = settledSources.contains(source)
                || (holdKey.type() == BillingReferenceType.APPEARANCE_REQUEST && settledHolds.contains(holdKey));
        if (settled) {
            throw new FakeBillingException(FakeBillingErrorCode.PAYOUT_ALREADY_SETTLED);
        }
    }

    private void markSettled(HoldKey holdKey, SourceKey source) {
        settledSources.add(source);
        settledHolds.add(holdKey);
    }

    /** 보류에서 금액을 빼서 받는 회원 잔액에 더한다 */
    private void moveFromHold(Hold hold, Long receiverId, long amount) {
        hold.remainingHp -= amount;
        balances.put(receiverId, balanceOf(receiverId) + amount);
    }

    private enum Action {
        RELEASE, PAYOUT, PENALTY
    }

    private record HoldKey(BillingReferenceType type, Long id) {
    }

    private record RequestKey(HoldKey hold, Action action, String requestId) {
    }

    private record SourceKey(SettlementSourceType type, Long id) {
    }

    private static final class Hold {

        private final Long ownerId;
        private final long originalHp;
        private long remainingHp;

        private Hold(Long ownerId, long originalHp) {
            this.ownerId = ownerId;
            this.originalHp = originalHp;
            this.remainingHp = originalHp;
        }
    }
}
