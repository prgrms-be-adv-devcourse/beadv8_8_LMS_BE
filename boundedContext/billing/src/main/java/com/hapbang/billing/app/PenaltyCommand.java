package com.hapbang.billing.app;

import java.util.Objects;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BillingReferenceType;
import com.hapbang.billing.domain.SettlementSourceType;

// 호스트가 성립한 출연을 일방 취소했을 때, 보류에서 정산 기준액의 30%(소수점 버림)를 게스트에게 위약금으로 지급하고
// 나머지를 호스트에게 반환하는 요청이다. 취소 가능 여부와 정산 기준액(취소된 출연의 미처리 보류액)은 출연이 정한다.
// 취소 ID는 보류 하나 안에서 고유하며, 같은 취소 사건을 다시 보낼 때는 같은 값을 쓴다.
public record PenaltyCommand(
        Long payerUserId,
        BillingReferenceType referenceType,
        Long referenceId,
        String cancellationId,
        Long receiverUserId,
        SettlementSourceType sourceType,
        Long sourceId,
        long baseAmountHp
) {

    // 호스트 출연 취소 위약금 비율(%): 계약한 출연료의 30% (루트 AGENTS.md 5장 출연 거래 정책 수치)
    static final long PENALTY_RATE_PERCENT = 30;

    // 위약금 정산 키의 접두사
    static final String KEY_PREFIX = "PENALTY";

    // 필수 값·취소 ID 형식을 확인하고, 정산 기준액이 0 이하이면 INVALID_POINT_AMOUNT, 위약금이 0 HP가 되면(기준액 1~3 HP)
    // PENALTY_AMOUNT_ZERO로 거절한다. 최소 출연료는 출연이 검증하며 Billing은 그 수치를 두지 않는다(지급 엔진은 강의도 쓴다).
    public PenaltyCommand {
        Objects.requireNonNull(payerUserId, "보류한 회원 ID는 필수입니다.");
        Objects.requireNonNull(referenceType, "참조 유형은 필수입니다.");
        Objects.requireNonNull(referenceId, "참조 ID는 필수입니다.");
        Objects.requireNonNull(receiverUserId, "받는 회원 ID는 필수입니다.");
        Objects.requireNonNull(sourceType, "지급 원천 유형은 필수입니다.");
        Objects.requireNonNull(sourceId, "지급 원천 ID는 필수입니다.");
        if (!HoldCommand.HOLDABLE_REFERENCE_TYPES.contains(referenceType)) {
            throw new IllegalArgumentException("보류할 수 없는 참조 유형입니다: " + referenceType);
        }
        if (cancellationId == null || !ReleaseCommand.RELEASE_REQUEST_ID.matcher(cancellationId).matches()) {
            throw new IllegalArgumentException("취소 ID 형식이 올바르지 않습니다: " + cancellationId);
        }
        if (baseAmountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        if (Math.multiplyExact(baseAmountHp, PENALTY_RATE_PERCENT) / 100 == 0) {
            throw new BillingException(BillingErrorCode.PENALTY_AMOUNT_ZERO);
        }
    }

    // 게스트 위약금 = floor(정산 기준액 × 30 / 100)
    long penaltyHp() {
        return Math.multiplyExact(baseAmountHp, PENALTY_RATE_PERCENT) / 100;
    }

    // 호스트 반환액 = 정산 기준액 - 게스트 위약금
    long releaseHp() {
        return baseAmountHp - penaltyHp();
    }

    // 지급할 보류를 찾는 보류 멱등키 HOLD:{referenceType}:{referenceId}를 만든다.
    String holdKey() {
        return HoldCommand.holdKey(referenceType, referenceId);
    }

    // 취소 사건 하나의 정산 키 PENALTY:{holdId}:{cancellationId}. 위약금 지급 건(Settlement)의 멱등키다.
    String settlementKey(Long holdId) {
        return KEY_PREFIX + ":" + holdId + ":" + cancellationId;
    }

    // 이 취소 사건의 호스트 반환 상세 멱등키이자 반환 원장 작업 키
    String releaseKey(Long holdId) {
        return settlementKey(holdId) + ":RELEASE";
    }

    // 이 취소 사건의 게스트 위약금 지급 상세 멱등키이자 지급 원장 작업 키
    String payoutKey(Long holdId) {
        return settlementKey(holdId) + ":PAYOUT";
    }
}
