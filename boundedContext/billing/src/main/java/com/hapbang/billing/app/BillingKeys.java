package com.hapbang.billing.app;

import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;

// 보류·반환·지급·위약금 정산의 멱등키(원장 작업 키)를 요청으로 결정론적으로 만든다.
// 키 형식은 Billing 내부 규칙이라 shared 요청 DTO에 두지 않는다(호출한 쪽은 키를 만들거나 넘기지 않는다, #145).
// 형식은 #78·#100·#134에서 정한 그대로이며, 바꾸면 기존 보류·상세·지급 건·원장을 다시 찾을 수 없으므로 바꾸지 않는다.
final class BillingKeys {

    static final String HOLD_PREFIX = "HOLD";
    static final String RELEASE_PREFIX = "RELEASE";
    static final String PAYOUT_PREFIX = "PAYOUT";
    static final String PENALTY_PREFIX = "PENALTY";
    static final String CHARGE_PREFIX = "CHARGE";

    private BillingKeys() {
    }

    // 참조 대상의 보류 멱등키 HOLD:{referenceType}:{referenceId}. 원장 작업 키로도 같은 값을 쓴다.
    // 참조 유형은 Enum 이름으로만 표기하므로 같은 대상이 다른 문자열로 표기되어 보류가 중복 생성되지 않는다.
    static String hold(BillingReferenceType referenceType, Long referenceId) {
        return HOLD_PREFIX + ":" + referenceType.name() + ":" + referenceId;
    }

    static String hold(HoldCommand command) {
        return hold(command.referenceType(), command.referenceId());
    }

    static String hold(ReleaseCommand command) {
        return hold(command.referenceType(), command.referenceId());
    }

    static String hold(PayoutCommand command) {
        return hold(command.referenceType(), command.referenceId());
    }

    static String hold(PenaltyCommand command) {
        return hold(command.referenceType(), command.referenceId());
    }

    // 충전 원장 작업 키 CHARGE:{chargeId}. 충전 건 하나에 충전 원장은 한 번만 기록된다 (#224).
    static String charge(Long chargeId) {
        return CHARGE_PREFIX + ":" + chargeId;
    }

    // 반환 멱등키 RELEASE:{holdId}:{releaseRequestId}. 반환 상세 멱등키이자 원장 작업 키다.
    static String release(Long holdId, ReleaseCommand command) {
        return RELEASE_PREFIX + ":" + holdId + ":" + command.releaseRequestId();
    }

    // 지급 키 PAYOUT:{holdId}:{payoutRequestId}. 지급 상세·지급 건(Settlement)의 멱등키이자 원장 작업 키다.
    static String payout(Long holdId, PayoutCommand command) {
        return PAYOUT_PREFIX + ":" + holdId + ":" + command.payoutRequestId();
    }

    // 취소 사건 하나의 정산 키 PENALTY:{holdId}:{cancellationId}. 위약금 지급 건(Settlement)의 멱등키다.
    static String penalty(Long holdId, PenaltyCommand command) {
        return PENALTY_PREFIX + ":" + holdId + ":" + command.cancellationId();
    }

    // 이 취소 사건의 호스트 반환 상세 멱등키이자 반환 원장 작업 키
    static String penaltyRelease(Long holdId, PenaltyCommand command) {
        return penalty(holdId, command) + ":RELEASE";
    }

    // 이 취소 사건의 게스트 위약금 지급 상세 멱등키이자 지급 원장 작업 키
    static String penaltyPayout(Long holdId, PenaltyCommand command) {
        return penalty(holdId, command) + ":PAYOUT";
    }
}
