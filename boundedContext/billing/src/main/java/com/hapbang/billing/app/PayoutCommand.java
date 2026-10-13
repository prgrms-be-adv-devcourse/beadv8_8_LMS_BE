package com.hapbang.billing.app;

import java.util.Objects;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BillingReferenceType;
import com.hapbang.billing.domain.SettlementSourceType;

// 참조 대상 하나의 보류에서 HP 일부(부분 지급) 또는 남은 보류 전부(전체 지급)를 받는 회원의 수익 HP로 옮기는 정상 지급 요청이다.
// 강의·출연이 지급 가능 여부와 금액을 정하고, Billing은 지갑·보류·금액·멱등성·금융 정합성만 검증한다.
// 지급 요청 ID는 보류 하나 안에서 고유하며, 같은 업무 요청을 다시 보낼 때는 같은 값을 쓴다(호출마다 새 UUID를 만들지 않는다).
public record PayoutCommand(
        Long payerUserId,
        BillingReferenceType referenceType,
        Long referenceId,
        String payoutRequestId,
        Long receiverUserId,
        SettlementSourceType sourceType,
        Long sourceId,
        Long amountHp
) {

    // 지급 멱등키와 원장 작업 키의 접두사
    static final String KEY_PREFIX = "PAYOUT";

    // 필수 값·지급 요청 ID 형식을 확인하고, 부분 지급 금액이 0 이하이면 INVALID_POINT_AMOUNT로 거절한다. 금액이 null이면 전체 지급이다.
    public PayoutCommand {
        Objects.requireNonNull(payerUserId, "보류한 회원 ID는 필수입니다.");
        Objects.requireNonNull(referenceType, "참조 유형은 필수입니다.");
        Objects.requireNonNull(referenceId, "참조 ID는 필수입니다.");
        Objects.requireNonNull(receiverUserId, "받는 회원 ID는 필수입니다.");
        Objects.requireNonNull(sourceType, "지급 원천 유형은 필수입니다.");
        Objects.requireNonNull(sourceId, "지급 원천 ID는 필수입니다.");
        if (!HoldCommand.HOLDABLE_REFERENCE_TYPES.contains(referenceType)) {
            throw new IllegalArgumentException("보류할 수 없는 참조 유형입니다: " + referenceType);
        }
        if (payoutRequestId == null || !ReleaseCommand.RELEASE_REQUEST_ID.matcher(payoutRequestId).matches()) {
            throw new IllegalArgumentException("지급 요청 ID 형식이 올바르지 않습니다: " + payoutRequestId);
        }
        if (amountHp != null && amountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
    }

    // 남은 보류액 중 요청한 금액만 지급하는 부분 지급 요청을 만든다.
    public static PayoutCommand partial(Long payerUserId, BillingReferenceType referenceType, Long referenceId,
            String payoutRequestId, Long receiverUserId, SettlementSourceType sourceType, Long sourceId,
            long amountHp) {
        return new PayoutCommand(payerUserId, referenceType, referenceId, payoutRequestId, receiverUserId,
                sourceType, sourceId, amountHp);
    }

    // 처리 시점에 남아 있는 보류액 전부를 지급하는 전체 지급 요청을 만든다.
    public static PayoutCommand full(Long payerUserId, BillingReferenceType referenceType, Long referenceId,
            String payoutRequestId, Long receiverUserId, SettlementSourceType sourceType, Long sourceId) {
        return new PayoutCommand(payerUserId, referenceType, referenceId, payoutRequestId, receiverUserId,
                sourceType, sourceId, null);
    }

    // 금액 없이 남은 보류 전부를 지급하는 요청인지 확인한다.
    public boolean isFull() {
        return amountHp == null;
    }

    // 지급할 보류를 찾는 보류 멱등키 HOLD:{referenceType}:{referenceId}를 만든다.
    String holdKey() {
        return HoldCommand.holdKey(referenceType, referenceId);
    }

    // 보류 ID와 지급 요청 ID로 결정론적인 지급 키 PAYOUT:{holdId}:{payoutRequestId}를 만든다.
    // 지급 상세·지급 건(Settlement)의 멱등키와 원장 작업 키로 같은 값을 쓴다.
    String idempotencyKey(Long holdId) {
        return KEY_PREFIX + ":" + holdId + ":" + payoutRequestId;
    }
}
